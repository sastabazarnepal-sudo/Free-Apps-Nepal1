package com.freeappsnepal.marketplace

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

// ==========================================
// 1. DATA MODELS
// ==========================================

data class AppModel(
    val id: String = "",
    val name: String = "",
    val developer: String = "",
    val type: String = "APP", // "APP" or "GAME"
    val category: String = "Tools",
    val version: String = "1.0.0",
    val size: String = "25 MB",
    val description: String = "",
    val freeEnabled: Boolean = true,
    val freeUrl: String = "",
    val proEnabled: Boolean = false,
    val proUrl: String = "",
    val isFeatured: Boolean = false,
    val isPopular: Boolean = false,
    val viewCount: Long = 0,
    val freeClicks: Long = 0,
    val proClicks: Long = 0
)

data class ProPlanConfig(
    val monthlyPrice: Double = 99.0,
    val discountEnabled: Boolean = false,
    val discountPercent: Double = 0.0
) {
    fun getFinalPrice(): Double {
        return if (discountEnabled && discountPercent > 0) {
            monthlyPrice - (monthlyPrice * discountPercent / 100.0)
        } else {
            monthlyPrice
        }
    }
}

data class PromoBanner(
    val title: String = "",
    val description: String = "",
    val targetUrl: String = "",
    val buttonText: String = "Explore",
    val active: Boolean = false
)

// ==========================================
// 2. VIEWMODEL & FIREBASE LOGIC
// ==========================================

class AppViewModel : ViewModel() {
    private val db: FirebaseFirestore = Firebase.firestore
    private val auth: FirebaseAuth = Firebase.auth

    val appsList = MutableStateFlow<List<AppModel>>(emptyList())
    val promoBanner = MutableStateFlow<PromoBanner?>(null)
    val proPlan = MutableStateFlow(ProPlanConfig())
    val isProUser = MutableStateFlow(false)
    val userEmail = MutableStateFlow<String?>(null)

    // Admin detection: change this to your desired admin email in Firebase Auth
    val isAdmin = MutableStateFlow(false)

    init {
        checkUserSession()
        listenToApps()
        listenToPromo()
        listenToProPlan()
    }

    fun checkUserSession() {
        val user = auth.currentUser
        if (user != null) {
            userEmail.value = user.email
            isAdmin.value = user.email?.trim()?.lowercase() == "admin@freeappsnepal.com"
            checkSubscriptionStatus(user.uid)
        } else {
            userEmail.value = null
            isAdmin.value = false
            isProUser.value = false
        }
    }

    private fun checkSubscriptionStatus(uid: String) {
        db.collection("userSubscriptions").document(uid)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null && snapshot.exists()) {
                    val expiresAt = snapshot.getTimestamp("expiresAt")
                    val now = Timestamp.now()
                    val active = snapshot.getBoolean("isPro") == true &&
                            expiresAt != null && expiresAt > now
                    isProUser.value = active
                } else {
                    isProUser.value = false
                }
            }
    }

    private fun listenToApps() {
        db.collection("apps").addSnapshotListener { snapshot, _ ->
            if (snapshot != null) {
                val list = snapshot.documents.mapNotNull { doc ->
                    doc.toObject(AppModel::class.java)?.copy(id = doc.id)
                }
                appsList.value = list
            }
        }
    }

    private fun listenToPromo() {
        db.collection("settings").document("promo_banner")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null && snapshot.exists()) {
                    val p = snapshot.toObject(PromoBanner::class.java)
                    promoBanner.value = if (p?.active == true) p else null
                } else {
                    promoBanner.value = null
                }
            }
    }

    private fun listenToProPlan() {
        db.collection("settings").document("pro_plan")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null && snapshot.exists()) {
                    proPlan.value = snapshot.toObject(ProPlanConfig::class.java) ?: ProPlanConfig()
                }
            }
    }

    fun logClick(item: AppModel, isPro: Boolean) {
        val field = if (isPro) "proClicks" else "freeClicks"
        db.collection("apps").document(item.id).update(field, FieldValue.increment(1))
    }

    // Direct simulated payment activator for verified testing/production webhook fallback
    fun activateProSubscription(onSuccess: () -> Unit) {
        val user = auth.currentUser ?: return
        viewModelScope.launch {
            val thirtyDays = Timestamp(Timestamp.now().seconds + (30 * 24 * 3600), 0)
            db.collection("userSubscriptions").document(user.uid).set(
                mapOf(
                    "userId" to user.uid,
                    "isPro" to true,
                    "status" to "ACTIVE",
                    "expiresAt" to thirtyDays,
                    "updatedAt" to Timestamp.now()
                )
            ).await()
            isProUser.value = true
            onSuccess()
        }
    }

    // ADMIN ACTIONS
    fun saveAppToFirebase(app: AppModel, onComplete: () -> Unit) {
        viewModelScope.launch {
            if (app.id.isEmpty()) {
                db.collection("apps").add(app).await()
            } else {
                db.collection("apps").document(app.id).set(app).await()
            }
            onComplete()
        }
    }

    fun deleteAppFromFirebase(appId: String) {
        db.collection("apps").document(appId).delete()
    }

    fun updateProPlanAdmin(newPrice: Double, discountEnabled: Boolean, discountPercent: Double) {
        db.collection("settings").document("pro_plan").set(
            ProPlanConfig(
                monthlyPrice = newPrice,
                discountEnabled = discountEnabled,
                discountPercent = discountPercent
            )
        )
    }

    fun updatePromoAdmin(promo: PromoBanner) {
        db.collection("settings").document("promo_banner").set(promo)
    }
}

// ==========================================
// 3. MAIN ACTIVITY & NAVIGATION
// ==========================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: AppViewModel = viewModel()
            val navController = rememberNavController()

            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFFD32F2F), // Coral Red
                    secondary = Color(0xFFB71C1C),
                    background = Color(0xFFF9F9FB),
                    surface = Color.White
                )
            ) {
                var selectedAppForDetail by remember { mutableStateOf<AppModel?>(null) }

                Scaffold(
                    bottomBar = {
                        val navBackStackEntry by navController.currentBackStackEntryAsState()
                        val currentRoute = navBackStackEntry?.destination?.route

                        NavigationBar(containerColor = Color.White) {
                            NavigationBarItem(
                                selected = currentRoute == "home",
                                onClick = { navController.navigate("home") },
                                icon = { Icon(Icons.Default.Home, contentDescription = null) },
                                label = { Text("Home") },
                                colors = NavigationBarItemDefaults.colors(selectedIconColor = Color(0xFFD32F2F))
                            )
                            NavigationBarItem(
                                selected = currentRoute == "pro",
                                onClick = { navController.navigate("pro") },
                                icon = { Icon(Icons.Default.WorkspacePremium, contentDescription = null) },
                                label = { Text("Pro") },
                                colors = NavigationBarItemDefaults.colors(selectedIconColor = Color(0xFFFFB300))
                            )
                            NavigationBarItem(
                                selected = currentRoute == "profile",
                                onClick = { navController.navigate("profile") },
                                icon = { Icon(Icons.Default.Person, contentDescription = null) },
                                label = { Text("Account") },
                                colors = NavigationBarItemDefaults.colors(selectedIconColor = Color(0xFFD32F2F))
                            )
                        }
                    }
                ) { padding ->
                    NavHost(
                        navController = navController,
                        startDestination = "home",
                        modifier = Modifier.padding(padding)
                    ) {
                        composable("home") {
                            HomeScreen(
                                viewModel = viewModel,
                                onAppClick = { item ->
                                    selectedAppForDetail = item
                                    navController.navigate("detail")
                                }
                            )
                        }
                        composable("detail") {
                            selectedAppForDetail?.let { item ->
                                DetailScreen(
                                    item = item,
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() },
                                    onNavigatePro = { navController.navigate("pro") }
                                )
                            }
                        }
                        composable("pro") {
                            ProSubscriptionScreen(viewModel = viewModel)
                        }
                        composable("profile") {
                            ProfileAndAdminScreen(
                                viewModel = viewModel,
                                navController = navController
                            )
                        }
                        composable("admin_dashboard") {
                            AdminDashboardScreen(viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// 4. UI SCREENS
// ==========================================

fun launchCustomTab(context: Context, url: String) {
    if (url.isBlank()) return
    try {
        val cleanUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) "https://$url" else url
        val customTabsIntent = CustomTabsIntent.Builder().build()
        customTabsIntent.launchUrl(context, Uri.parse(cleanUrl))
    } catch (_: Exception) {
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(browserIntent)
    }
}

@Composable
fun HomeScreen(
    viewModel: AppViewModel,
    onAppClick: (AppModel) -> Unit
) {
    val apps by viewModel.appsList.collectAsState()
    val promo by viewModel.promoBanner.collectAsState()
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF9F9FB))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Free Apps Nepal",
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFFD32F2F)
            )
            Text(
                text = "Discover Top Apps & Direct Pro Downloads",
                fontSize = 12.sp,
                color = Color.Gray
            )
        }

        // Active Admin Promotion Banner
        promo?.let { banner ->
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { launchCustomTab(context, banner.targetUrl) },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(banner.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(banner.description, color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { launchCustomTab(context, banner.targetUrl) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(banner.buttonText, color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Category Sections
        val featuredApps = apps.filter { it.isFeatured }
        if (featuredApps.isNotEmpty()) {
            item {
                Text("Featured Applications", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(featuredApps) { app ->
                        AppCard(app = app, onClick = { onAppClick(app) })
                    }
                }
            }
        }

        val allApps = apps.filter { it.type == "APP" }
        if (allApps.isNotEmpty()) {
            item {
                Text("Latest Apps", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            items(allApps) { app ->
                AppListItem(app = app, onClick = { onAppClick(app) })
            }
        }

        val games = apps.filter { it.type == "GAME" }
        if (games.isNotEmpty()) {
            item {
                Text("Trending Games", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            items(games) { game ->
                AppListItem(app = game, onClick = { onAppClick(game) })
            }
        }
    }
}

@Composable
fun AppCard(app: AppModel, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .size(120.dp, 80.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFFFEBEE)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Android, contentDescription = null, tint = Color(0xFFD32F2F), modifier = Modifier.size(40.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(app.name, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.developer, color = Color.Gray, fontSize = 11.sp, maxLines = 1)
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (app.freeEnabled) BadgeLabel("FREE", Color(0xFF2E7D32), Color(0xFFE8F5E9))
                if (app.proEnabled) BadgeLabel("PRO", Color(0xFFC5A059), Color(0xFFFFF8E1))
            }
        }
    }
}

@Composable
fun AppListItem(app: AppModel, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFFFEBEE)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Android, contentDescription = null, tint = Color(0xFFD32F2F))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(app.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("${app.developer} • ${app.size}", color = Color.Gray, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (app.freeEnabled) BadgeLabel("FREE", Color(0xFF2E7D32), Color(0xFFE8F5E9))
                    if (app.proEnabled) BadgeLabel("PRO", Color(0xFFC5A059), Color(0xFFFFF8E1))
                }
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray)
        }
    }
}

@Composable
fun BadgeLabel(text: String, textColor: Color, bgColor: Color) {
    Box(
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertica
                    
