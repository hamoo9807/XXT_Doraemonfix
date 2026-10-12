package com.cofbro.qian.data

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object URL {
    // 登录接口 - DEPRECATED: 密码明文放在GET查询字符串中有安全风险, 改用 fanyalogin POST + AES加密
    fun getLoginPath(username: String, password: String): String =
        "https://passport2-api.chaoxing.com/v11/loginregister?code=$password&cx_xxt_passport=json&uname=$username&loginType=1&roleSelect=true"

    // 安全的 fanyalogin 登录接口 (POST, AES加密凭证)
    const val FANYALOGIN_URL = "https://passport2.chaoxing.com/fanyalogin"

    fun getFanyaloginPath(): String = FANYALOGIN_URL

    private const val AES_KEY = "u2oh6Vu^HWe4_AES"

    fun encryptAES(message: String): String {
        if (message.isEmpty()) return message
        return try {
            val key = AES_KEY.toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(key))
            Base64.encodeToString(cipher.doFinal(message.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        } catch (e: Exception) { message }
    }

    fun decryptAES(encrypted: String): String {
        if (encrypted.isEmpty()) return encrypted
        return try {
            val key = AES_KEY.toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(key))
            String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) { encrypted }
    }

    // 获取所有课程
    fun getAllCourseListPath(): String = "http://mooc1-api.chaoxing.com/mycourse/backclazzdata"

    // 获得头像地址
    fun getAvtarImgPath(uid: String): String = "http://photo.chaoxing.com/p/${uid}_80"

    // 查询所有活动
    fun gatActiveTaskListPath(courseId: String, classId: String, uid: String, cpi: String): String =
        "https://mobilelearn.chaoxing.com/ppt/activeAPI/taskactivelist?courseId=$courseId&classId=$classId&uid=$uid&cpi=$cpi"

    // 获取签到类型
    fun getSignType(activeId: String): String =
        "https://mobilelearn.chaoxing.com/newsign/signDetail?activePrimaryId=$activeId&type=1"

    fun getSignCodePath(activeId: String, classId: String, courseId: String): String =
        "https://mobilelearn.chaoxing.com/widget/sign/pcTeaSignController/endSign?activeId=$activeId&classId=$classId&courseId=$courseId&isTeacherViewOpen=1"

    fun getSignWithCameraPath(aid: String, location: String = ""): String =
        "https://mobilelearn.chaoxing.com/pptSign/stuSignajax?activeId=$aid&location=$location"

    // 普通签到
    fun getNormalSignPath(
        courseId: String,
        classId: String,
        aid: String,
        signCode: String = ""
    ): String =
        "https://mobilelearn.chaoxing.com/widget/sign/pcStuSignController/signIn?courseId=$courseId&classId=$classId&activeId=$aid&signCode=$signCode&validate="

    fun getLocationSignPath(
        address: String?,
        aid: String,
        uid: String,
        lat: String?,
        long: String?,
    ): String {
        val addrParam = address?.let { "&address=$it" } ?: ""
        val latParam = lat?.let { "&latitude=$it" } ?: ""
        val lonParam = long?.let { "&longitude=$it" } ?: ""
        return "https://mobilelearn.chaoxing.com/pptSign/stuSignajax?activeId=$aid&uid=$uid&clientip=0.0.0.0${latParam}${lonParam}${addrParam}&fid=&appType=15&ifTiJiao=1"
    }

    fun getUploadToken(): String = "https://pan-yz.chaoxing.com/api/token/uservalid"

    fun getUploadImagePath(token: String): String =
        "https://pan-yz.chaoxing.com/upload?_token=$token"

    fun getSignWithPhoto(aid: String, uid: String, objectId: String): String =
        "https://mobilelearn.chaoxing.com/pptSign/stuSignajax?activeId=$aid&uid=$uid&appType=15&fid=0&objectId=$objectId"

    fun getWorkEncPath(courseId: String, classId: String, cpi: String): String =
        "https://mooc1-2.chaoxing.com/mooc-ans/visit/stucoursemiddle?courseid=$courseId&clazzid=$classId&vc=1&cpi=$cpi&ismooc2=1&v=2"

    // 作业列表
    fun getHomeworkListPath(
        courseId: String,
        classId: String,
        cpi: String,
        workEnc: String
    ): String =
        "https://mooc1.chaoxing.com/mooc2/work/list?courseId=$courseId&classId=$classId&cpi=$cpi&ut=s&enc=$workEnc"

    // 用户信息
    fun getUserInfo(): String = "http://i.chaoxing.com/base"

    // 验证码
    fun getSendCaptchaUrl(): String = "https://passport2-api.chaoxing.com/api/sendcaptcha"

    fun getLoginWithSmsUrl(): String =
        "https://passport2-api.chaoxing.com/v11/loginregister?cx_xxt_passport=json"

    fun getAnalysisPath(aid: String): String =
        "https://mobilelearn.chaoxing.com/pptSign/analysis?DB_STRATEGY=RANDOM&aid=$aid&vs=1"

    fun getAnalysis2Path(analysis2Code: String): String = "https://mobilelearn.chaoxing.com/pptSign/analysis2?DB_STRATEGY=RANDOM&code=$analysis2Code"

    fun checkSignCodePath(aid: String, signCode: String): String = "https://mobilelearn.chaoxing.com/widget/sign/pcStuSignController/checkSignCode?activeId=$aid&signCode=$signCode"

    // 获取活动详细信息(otherId + ifphoto)
    fun getActiveInfoPath(activeId: String): String =
        "https://mobilelearn.chaoxing.com/v2/apis/active/getPPTActiveInfo?activeId=$activeId&duid=&denc="

    // 获取PUID(上传照片需要)
    fun getPuidPath(): String = "https://sso.chaoxing.com/apis/login/userLogin4Uname.do"

    // POST签到基础URL
    const val SIGN_POST_URL = "https://mobilelearn.chaoxing.com/pptSign/stuSignajax"

    // 预签到
    fun getPreSignPath(courseId: String, classId: String, activeId: String, uid: String): String =
        "https://mobilelearn.chaoxing.com/newsign/preSign?courseId=$courseId&classId=$classId&activePrimaryId=$activeId&general=1&sys=1&ls=1&appType=15&tid=&uid=$uid&ut=s"
}