package com.cofbro.qian.main

import android.os.Bundle
import android.view.ViewGroup.MarginLayoutParams
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.cofbro.hymvvmutils.base.BaseActivity
import com.cofbro.qian.R
import com.cofbro.qian.databinding.ActivityMainBinding
import com.cofbro.qian.home.HomeFragment
import com.cofbro.qian.profile.ProfileFragment
import com.cofbro.qian.utils.AmapUtils
import com.cofbro.qian.utils.CacheUtils
import com.cofbro.qian.utils.Constants
import com.cofbro.qian.utils.Constants.BACK_PRESSED_INTERVAL
import com.hjq.toast.ToastUtils

class MainActivity : BaseActivity<MainViewModel, ActivityMainBinding>() {
    private var currentBackPressedTime = 0L
    private var homeFragment: HomeFragment? = null
    private var settingsFragment: ProfileFragment? = null
    private var lastShowFragment: Fragment? = null
    private var contentId = -1

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        AmapUtils.checkLocationPermission(this)
        CacheUtils.activities[Constants.Cache.MAIN_ACTIVITY] = this
        initView()
        changeNavigationResponsively()
    }

    private fun saveBottomBarHeight(virtualHeight: Int) {
        CacheUtils.cache[Constants.Cache.BOTTOM_BAR_HEIGHT] =
            (binding?.navigationBar?.height?.plus(virtualHeight)).toString()
    }

    private fun initView() {
        contentId = binding?.content?.id ?: -1
        initFirstFragment()

        binding?.navigationBar?.setOnItemSelectedListener { item ->
            if (item.itemId != binding?.navigationBar?.selectedItemId) {
                when (item.itemId) {
                    R.id.tab_courses -> {
                        if (homeFragment == null) {
                            homeFragment = HomeFragment()
                            supportFragmentManager.beginTransaction()
                                .add(contentId, homeFragment!!, "HomeFragment")
                                .commit()
                        }
                        showFragment(homeFragment!!)
                    }
                    R.id.tab_settings -> {
                        if (settingsFragment == null) {
                            settingsFragment = ProfileFragment()
                            supportFragmentManager.beginTransaction()
                                .add(contentId, settingsFragment!!, "SettingsFragment")
                                .commit()
                        }
                        showFragment(settingsFragment!!)
                    }
                }
                return@setOnItemSelectedListener true
            }
            false
        }
    }

    private fun initFirstFragment() {
        // 首页=课程页面
        homeFragment = HomeFragment()
        supportFragmentManager.beginTransaction()
            .show(homeFragment!!)
            .add(contentId, homeFragment!!, "HomeFragment")
            .commit()
        lastShowFragment = homeFragment
    }

    private fun showFragment(fragmentToShow: Fragment) {
        val transaction = supportFragmentManager.beginTransaction()
        transaction.show(fragmentToShow)
        lastShowFragment?.let { transaction.hide(it) }
        lastShowFragment = fragmentToShow
        transaction.commit()
    }

    private fun changeNavigationResponsively() {
        binding?.root?.post {
            val windowInsects = ViewCompat.getRootWindowInsets(window.decorView)
            val height = windowInsects?.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.navigationBars()
            )?.bottom ?: 0
            updateLayoutParams(height)
            saveBottomBarHeight(height)
        }
    }

    private fun updateLayoutParams(height: Int) {
        if (height > 80) {
            val layout = binding?.root?.layoutParams as? MarginLayoutParams
            layout?.bottomMargin = height
            binding?.root?.layoutParams = layout
        }
    }

    override fun onBackPressed() {
        if (System.currentTimeMillis() - currentBackPressedTime > BACK_PRESSED_INTERVAL) {
            currentBackPressedTime = System.currentTimeMillis()
            ToastUtils.show("再按一次退出")
            return
        }
        super.onBackPressed()
    }
}
