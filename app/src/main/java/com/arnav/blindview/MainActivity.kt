package com.arnav.blindview

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.documentfile.provider.DocumentFile

private val Pink = Color(0xFFD94F91)
private val Ink = Color(0xFF171923)
private val Canvas = Color(0xFFF6F4F8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BlindViewApp() }
    }
}

data class StoryProfile(val username: String, val subtitle: String, val tint: Color)

@Composable fun BlindViewApp() {
    var tab by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<StoryProfile?>(null) }
    var query by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(setOf<String>()) }
    var localMedia by remember { mutableStateOf(listOf<Uri>()) }
    var dark by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        localMedia = (localMedia + uris).distinct()
    }
    val profiles = remember { listOf(
        StoryProfile("your_story", "Add local media", Pink),
        StoryProfile("manas.jain123", "Example profile · connect officially", Color(0xFF8A8D98)),
        StoryProfile("jain_yashaswi", "Example profile · connect officially", Color(0xFF8A8D98)),
        StoryProfile("shouryaman", "Example profile · connect officially", Color(0xFF8A8D98)),
        StoryProfile("sarthak.patel", "Example profile · connect officially", Color(0xFF8A8D98)),
        StoryProfile("angad_chhabra", "Example profile · connect officially", Color(0xFF8A8D98))
    ) }
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary=Pink, background=Color(0xFF101116), surface=Color(0xFF1B1C23)) else lightColorScheme(primary=Pink, background=Canvas, surface=Color.White)) {
        Surface(Modifier.fillMaxSize(), color=MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal=22.dp, vertical=18.dp), verticalAlignment=Alignment.CenterVertically) {
                    Text("◉", color=Pink, fontSize=30.sp, fontWeight=FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    Text("BlindView", fontSize=27.sp, color=MaterialTheme.colorScheme.onBackground, fontWeight=FontWeight.Light)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick={ picker.launch(arrayOf("image/*","video/*")) }) { Icon(Icons.Default.Add, "Import media") }
                }
                when(tab) {
                    0 -> HomeScreen(profiles, localMedia.size, onImport={picker.launch(arrayOf("image/*","video/*"))}, onOpen={selected=it})
                    1 -> Column(Modifier.fillMaxSize().padding(20.dp)) {
                        Text("Search", fontSize=26.sp, fontWeight=FontWeight.Bold)
                        Spacer(Modifier.height(14.dp))
                        OutlinedTextField(query, {query=it}, Modifier.fillMaxWidth(), placeholder={Text("Search a username")}, leadingIcon={Icon(Icons.Default.Search,null)}, singleLine=true)
                        Spacer(Modifier.height(12.dp))
                        profiles.filter { it.username.contains(query,true) }.forEach { ProfileRow(it, saved.contains(it.username), { saved=if(saved.contains(it.username)) saved-it.username else saved+it.username }, {selected=it}) }
                        Text("Search is a local UI placeholder until an approved Instagram integration is configured.", color=Color.Gray, fontSize=12.sp)
                    }
                    2 -> Column(Modifier.fillMaxSize().padding(20.dp)) {
                        Text("Favorites", fontSize=26.sp, fontWeight=FontWeight.Bold)
                        val favorites=profiles.filter{saved.contains(it.username)}
                        if(favorites.isEmpty()) Text("Profiles you save will appear here.", color=Color.Gray)
                        favorites.forEach { ProfileRow(it,true,{saved=saved-it.username},{selected=it}) }
                    }
                    else -> SettingsScreen(dark,{dark=it},localMedia.size,{picker.launch(arrayOf("image/*","video/*"))})
                }
                Spacer(Modifier.weight(1f))
                NavigationBar(containerColor=MaterialTheme.colorScheme.surface) {
                    listOf("Home","Search","Favorites","Settings").forEachIndexed { i,label ->
                        NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Icon(when(i){0->Icons.Default.Home;1->Icons.Default.Search;2->Icons.Default.Favorite;else->Icons.Default.Settings},null)},label={Text(label)},colors=NavigationBarItemDefaults.colors(selectedIconColor=Pink,indicatorColor=Pink.copy(alpha=.12f)))
                    }
                }
            }
            selected?.let { StoryDialog(it, saved.contains(it.username), {saved=if(saved.contains(it.username)) saved-it.username else saved+it.username}) {selected=null} }
        }
    }
}

@Composable fun HomeScreen(profiles: List<StoryProfile>, mediaCount: Int, onImport:()->Unit, onOpen:(StoryProfile)->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp)) {
        Text("Hurry Up!", fontSize=25.sp, fontWeight=FontWeight.Bold, color=MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(16.dp))
        LazyVerticalGrid(columns=GridCells.Fixed(3), modifier=Modifier.height(390.dp), userScrollEnabled=false, verticalArrangement=Arrangement.spacedBy(18.dp), horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            items(profiles) { p -> Column(horizontalAlignment=Alignment.CenterHorizontally, modifier=Modifier.clickable { onOpen(p) }) {
                Box(Modifier.size(88.dp).border(3.dp, if(p.username=="your_story") Pink else Color.Gray, CircleShape).padding(5.dp).clip(CircleShape).background(p.tint), contentAlignment=Alignment.Center) {
                    Text(if(p.username=="your_story") "+" else p.username.take(1).uppercase(), color=Color.White, fontSize=28.sp, fontWeight=FontWeight.Bold)
                }
                Spacer(Modifier.height(7.dp))
                Text(p.username, maxLines=1, fontSize=13.sp)
                Text(if(p.username=="your_story") "Add media" else "—", color=Color.Gray, fontSize=12.sp)
            } }
        }
        Card(shape=RoundedCornerShape(22.dp), colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface), modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("Your media", fontWeight=FontWeight.Bold, fontSize=18.sp)
                Text("$mediaCount photos or videos selected on this device", color=Color.Gray, fontSize=13.sp)
                Spacer(Modifier.height(12.dp))
                Button(onClick=onImport, modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Import photos or videos") }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("Instagram account connection and remote story access require an approved Meta integration. This build does not collect passwords or bypass private-account protections.", color=Color.Gray, fontSize=12.sp)
    }
}

@Composable fun ProfileRow(p:StoryProfile, isSaved:Boolean, toggle:()->Unit, open:()->Unit) {
    Row(Modifier.fillMaxWidth().clickable{open()}.padding(vertical=9.dp), verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(p.tint), contentAlignment=Alignment.Center) { Text(p.username.take(1).uppercase(), color=Color.White, fontWeight=FontWeight.Bold) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(p.username,fontWeight=FontWeight.SemiBold); Text(p.subtitle,color=Color.Gray,fontSize=12.sp) }
        IconButton(onClick=toggle){Icon(if(isSaved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,null,tint=Pink)}
    }
}

@Composable fun SettingsScreen(dark:Boolean, setDark:(Boolean)->Unit, count:Int, import:()->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Settings",fontSize=26.sp,fontWeight=FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Card(shape=RoundedCornerShape(22.dp)) { Column(Modifier.padding(18.dp)) {
            Text("Account",fontWeight=FontWeight.Bold,fontSize=18.sp)
            Text("No Instagram credentials are stored in this app.",color=Color.Gray)
            TextButton(onClick={}) { Text("Connect using official Meta authorization (not configured)") }
        } }
        Spacer(Modifier.height(12.dp))
        Card(shape=RoundedCornerShape(22.dp)) { Column(Modifier.padding(18.dp)) {
            Text("Preferences",fontWeight=FontWeight.Bold,fontSize=18.sp)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("Dark appearance",Modifier.weight(1f)); Switch(dark,setDark)}
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("Local media library · $count items",Modifier.weight(1f)); TextButton(onClick=import){Text("Import")}}
        } }
        Spacer(Modifier.height(12.dp))
        Card(shape=RoundedCornerShape(22.dp)) { Column(Modifier.padding(18.dp)) {
            Text("Membership",fontWeight=FontWeight.Bold,fontSize=18.sp)
            Text("Premium UI placeholder. No paid access is sold in this prototype.",color=Color.Gray,fontSize=13.sp)
        } }
        Spacer(Modifier.height(12.dp))
        Text("Privacy · This starter stores no Instagram password and currently keeps favorites and settings in memory only.",color=Color.Gray,fontSize=12.sp)
    }
}

@Composable fun StoryDialog(p:StoryProfile, saved:Boolean, toggle:()->Unit, close:()->Unit) {
    AlertDialog(onDismissRequest=close, title={Text(p.username)}, text={
        Column {
            Text("This profile is a visual demo. Live Instagram stories are not fetched in this build.",color=Color.Gray)
            Spacer(Modifier.height(12.dp))
            Text("Use the device media picker on Home to select media you own or have permission to view and save.")
        }
    }, confirmButton={TextButton(onClick=toggle){Text(if(saved) "Remove favorite" else "Add favorite")}}, dismissButton={TextButton(onClick=close){Text("Close")}})
}
