package com.cofbro.qian.login

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.cofbro.hymvvmutils.base.BaseActivity
import com.cofbro.hymvvmutils.base.getBySp
import com.cofbro.hymvvmutils.base.saveUsedSp
import com.cofbro.qian.data.URL
import com.cofbro.qian.databinding.ActivityLoginBinding
import com.cofbro.qian.login.sms.SMSActivity
import com.cofbro.qian.main.MainActivity
import com.cofbro.qian.utils.AccountManager
import com.cofbro.qian.utils.CacheUtils
import com.cofbro.qian.utils.Constants
import com.cofbro.qian.utils.CookieRefresher
import com.cofbro.qian.utils.getIntExt
import com.cofbro.qian.utils.safeParseToJson
import com.hjq.toast.ToastUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


class LoginActivity : BaseActivity<LoginViewModel, ActivityLoginBinding>() {
    private var mUsername: String? = null
    private var mPassword: String? = null
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        showGuideIfFirstLaunch()
        tryLogin()
        initObserver()
        initEvent()
        autoClearFocus()
        login()
    }

    /**
     * 首次启动显示使用攻略, 告知用户账号来源、配置项、Cookie用法
     * 看过一次后不再弹出 (SharedPreferences 持久化标记)
     */
    private fun showGuideIfFirstLaunch() {
        val prefs = getSharedPreferences("app_guide", MODE_PRIVATE)
        if (prefs.getBoolean("guide_shown", false)) return

        // ★ 检测是否已绑定过账号 → 老用户自动跳过攻略
        val hasSavedAccount = !getBySp("username").isNullOrEmpty()
                || CacheUtils.cache["uid"]?.isNotEmpty() == true
                || kotlin.runCatching {
                    AccountManager.loadAllAccountData(this)
                        .getIntExt(Constants.Account.SIZE)
                }.getOrDefault(0) > 0
        if (hasSavedAccount) {
            prefs.edit().putBoolean("guide_shown", true).apply()
            return
        }

        val guideText = """
            👋 欢迎使用 Doraemon 签到助手

            ━━━ 账号登录 ━━━
            本页面的"账号密码登录"和"手机验证码登录"
            均使用 学习通 官方接口。
            请输入你的 学习通手机号 + 密码 (或短信验证码)
            进行登录, 登录后将自动保存Cookie用于后续签到。

            ━━━ 高德地图 Key (定位签到必备) ━━━
            部分签到需要精确GPS定位, 如果定位失败或偏差大,
            请在 个人中心 → 高德Key 中自行配置:
            1️⃣ 前往 https://lbs.amap.com 注册并创建应用
            2️⃣ 获取 Web服务 API Key
            3️⃣ 粘贴到 App 设置中保存
            ⚠️ 没有配置高德Key时, 定位签到可能失败!

            ━━━ Cookie 导出/导入 ━━━
            登录后, 在 个人中心 → 导出Cookie 可复制当前
            账号的Cookie JSON。将此JSON发送给其他人,
            对方在 个人中心 → 导入Cookie 粘贴后即可使用
            你的账号代签 (无需知道密码)。
            导入时填写手机号+密码可支持自动重登续期。

            ━━━ 其他配置 ━━━
            • 个人中心 → 预留地址: 设置常用签到位置
            • 个人中心 → 代签所有绑定账号: 开启后主号签到
              成功会自动为已绑定的其他账号代签
            • 首页右上角 LOG: 查看签到报告/操作日志

            更多帮助: 个人中心 → 📖 使用攻略
        """.trimIndent()

        val scrollView = ScrollView(this)
        val textView = TextView(this).apply {
            text = guideText
            textSize = 13f
            setPadding(28, 20, 28, 20)
            setLineSpacing(6f, 1.1f)
            setTextIsSelectable(true)
        }
        scrollView.addView(textView)

        AlertDialog.Builder(this)
            .setTitle("📖 使用攻略 (仅首次显示)")
            .setView(scrollView)
            .setPositiveButton("知道了") { _, _ ->
                prefs.edit().putBoolean("guide_shown", true).apply()
            }
            .setCancelable(false)
            .show()
    }

    private fun tryLogin() {
        // 优先从HYVM SP读取, 回退到CacheUtils (修复: HYVM SP偶尔写不进去导致自动登录失效)
        var username = getBySp("username")
        var password = getBySp("password")
        if (username.isNullOrEmpty()) username = CacheUtils.cache[Constants.USER.USERNAME]
        if (password.isNullOrEmpty()) password = CacheUtils.cache["password"]
        // ★ AES解密 (兼容旧版明文密码: 解密失败时回退原文)
        if (!password.isNullOrEmpty()) password = AccountManager.decryptPassword(password)
        if (!username.isNullOrEmpty() && !password.isNullOrEmpty()) {
            mUsername = username
            mPassword = password
            viewModel.login(URL.getLoginPath(username, password))
        }
    }

    private fun initObserver() {
        viewModel.loginLiveData.observe(this) { response ->
            val data = response.data ?: return@observe
            lifecycleScope.launch(Dispatchers.IO) {
                val body = data.body?.string()?.safeParseToJson()
                val headers = data.headers
                if (body?.getBoolean("status") == true) {
                    val list: List<String> = headers.values("Set-Cookie")
                    val cookies = StringBuilder()
                    var uid: String? = null
                    var fid: String? = null
                    if (list.isNotEmpty()) {
                        for (i in list.indices) {
                            val temp = list[i].split(";".toRegex()).dropLastWhile { it.isEmpty() }
                                .toTypedArray()[0]
                            cookies.append(temp).append(";")
                            if (temp.startsWith("UID")) uid = temp.substring(4)
                            //if (temp.startsWith("JSESSIONID")) continue
                            if (temp.startsWith("fid")) fid = temp.substring(4)
                        }
                    } else {
                        ToastUtils.show("Cookies获取失败!")
                    }
                    saveUserInfo()
                    CacheUtils.persistCookies(
                        cookies.toString(),
                        uid ?: "",
                        fid ?: ""
                    )
                    if (!mUsername.isNullOrEmpty()) {
                        CacheUtils.persistCookie(Constants.USER.USERNAME, mUsername!!)
                        // ★ 密码AES加密后存到CacheUtils, 防止HYVM SP写失败导致自动登录失效
                        CacheUtils.cache["password"] = AccountManager.encryptPassword(mPassword ?: "")
                    }
                    CookieRefresher.recordLoginTime()
                    lifecycleScope.launch(Dispatchers.Main) {
                        ToastUtils.show("登录成功！")
                        val intent = Intent(this@LoginActivity, MainActivity::class.java)
                        startActivity(intent)
                        finish()
                    }
                } else {
                    ToastUtils.show("账号或密码错误!")
                }
            }
        }
    }

    private fun saveUserInfo() {
        if (!mUsername.isNullOrEmpty() && !mPassword.isNullOrEmpty()) {
            saveUsedSp("username", mUsername!!)
            // ★ AES加密后存储, 防止明文泄露
            saveUsedSp("password", AccountManager.encryptPassword(mPassword!!))
        }
    }

    private fun initEvent() {
        // 清除输入框焦点
        autoClearFocus()
        // 登录
        login()
        // 手机号登录
        phoneLogin()
    }

    private fun phoneLogin() {
        binding?.tvPhoneLogin?.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                toVerifyCodeActivity()
            }
        }
    }

    private fun login() {
        binding?.tvLogin?.setOnClickListener {
            mUsername = binding?.ipUsername?.getTextString()
            mPassword = binding?.ipPassword?.getTextString()
            if (!mUsername.isNullOrEmpty() && !mPassword.isNullOrEmpty()) {
                viewModel.login(URL.getLoginPath(mUsername!!, mPassword!!))
            }
        }
    }

    private fun toVerifyCodeActivity() {
        val phoneNumber = binding?.ipUsername?.getTextString() ?: ""
        if (phoneNumber.length != 11) {
            ToastUtils.show("请输入正确的手机号")
            return
        }
        val intent = Intent(this, SMSActivity::class.java)
        intent.putExtra("phoneNumber", phoneNumber)
        CacheUtils.activities[Constants.Cache.LOGIN_ACTIVITY] = this
        startActivity(intent)
    }

    private fun autoClearFocus() {
        binding?.root!!.setOnClickListener {
            it.clearFocus()
        }
    }
}
