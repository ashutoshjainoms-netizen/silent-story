package com.ashu.ghostview

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener { show(it.itemId); true }
        if (savedInstanceState == null) show(R.id.nav_home)
    }

    override fun onResume() {
        super.onResume()
        if (!Ig.isLoggedIn()) startActivity(Intent(this, LoginActivity::class.java))
    }

    private fun show(id: Int) {
        val fm = supportFragmentManager
        val tag = "tab_$id"
        val tx = fm.beginTransaction()
        fm.fragments.forEach { tx.hide(it) }
        val existing = fm.findFragmentByTag(tag)
        if (existing != null) {
            tx.show(existing)
        } else {
            val f: Fragment = when (id) {
                R.id.nav_search -> SearchFragment()
                R.id.nav_favorites -> FavoritesFragment()
                R.id.nav_settings -> SettingsFragment()
                else -> HomeFragment()
            }
            tx.add(R.id.container, f, tag)
        }
        tx.commit()
    }
}
