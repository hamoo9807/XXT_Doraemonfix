package com.cofbro.qian.account

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.alibaba.fastjson.JSONObject
import com.cofbro.qian.data.URL
import com.cofbro.qian.utils.AccountManager
import com.cofbro.qian.utils.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 通过链接导入代签账号
 * 支持格式: chaoxingsignfaker://import?phone=xxx&pwd=xxx&name=xxx
 * 也支持扫码导入: QR码内容为json格式 {"phone":"xxx","pwd":"xxx","name":"xxx"}
 */
class ImportOtherUserActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val data = intent.data
        if (data != null) {
            // Deep link: chaoxingsignfaker://import?phone=xxx&pwd=xxx&name=xxx
            val phone = data.getQueryParameter("phone")
            val pwd = data.getQueryParameter("pwd")
            val name = data.getQueryParameter("name")
            if (!phone.isNullOrEmpty() && !pwd.isNullOrEmpty()) {
                importUser(phone, pwd, name ?: phone)
                return
            }
        }

        // 如果intent有extra数据(来自扫码)
        val jsonStr = intent.getStringExtra("import_json")
        if (!jsonStr.isNullOrEmpty()) {
            try {
                val json = JSONObject.parseObject(jsonStr)
                val phone = json.getString("phone") ?: json.getString("uname") ?: ""
                val pwd = json.getString("pwd") ?: json.getString("password") ?: ""
                val name = json.getString("name") ?: json.getString("username") ?: phone
                if (phone.isNotEmpty() && pwd.isNotEmpty()) {
                    importUser(phone, pwd, name)
                    return
                }
            } catch (e: Exception) {
                Toast.makeText(this, "解析失败: ${e.message}", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
        }

        Toast.makeText(this, "无效的导入链接", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun importUser(phone: String, pwd: String, name: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 校验登录态(失败不影响导入)
                NetworkUtils.request(NetworkUtils.buildClientRequest(URL.getPuidPath()))
                val account = JSONObject().apply {
                    put("username", name)
                    put("password", AccountManager.encryptPassword(pwd))
                    put("uid", phone) // 先用phone占位, 实际签到时会重新获取
                    put("cookies", "")
                    put("fid", "")
                    put("picUrl", "")
                }
                val accountData = AccountManager.loadAllAccountData(this@ImportOtherUserActivity)
                val updatedData = AccountManager.bindAccounts(this@ImportOtherUserActivity, accountData, account)
                if (updatedData != null) {
                    AccountManager.updateAccountData(this@ImportOtherUserActivity, updatedData.toJSONString())
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ImportOtherUserActivity, "账号 $name 导入成功！可前往个人中心→关联账号查看", Toast.LENGTH_LONG).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ImportOtherUserActivity, "导入失败（账号已存在？）", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ImportOtherUserActivity, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            finish()
        }
    }
}
