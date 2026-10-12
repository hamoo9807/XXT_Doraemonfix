package com.cofbro.qian.home

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alibaba.fastjson.JSONObject
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import com.cofbro.hymvvmutils.base.BaseFragment
import com.cofbro.hymvvmutils.base.BaseResponse
import com.cofbro.hymvvmutils.base.DataState
import com.cofbro.hymvvmutils.base.getBySp
import com.cofbro.qian.data.URL
import com.cofbro.qian.databinding.FragmentHomeBinding
import com.cofbro.qian.scan.ScanActivity
import com.cofbro.qian.signer.ProxySignManager
import com.cofbro.qian.utils.CacheUtils
import com.cofbro.qian.utils.CaptchaHelper
import com.cofbro.qian.utils.Constants
import com.cofbro.qian.utils.DebugLogCollector
import com.cofbro.qian.utils.Downloader
import com.cofbro.qian.utils.HtmlParser
import com.cofbro.qian.utils.NativeLocationUtils
import com.cofbro.qian.utils.NetworkUtils
import com.cofbro.qian.utils.PresetLocationManager
import com.cofbro.qian.utils.dp2px
import com.cofbro.qian.utils.getStatusBarHeight
import com.cofbro.qian.utils.safeParseToJson
import com.cofbro.qian.view.dialog.SignReportDialog
import com.cofbro.qian.wrapper.WrapperActivity
import com.hjq.toast.ToastUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody

class HomeFragment : BaseFragment<HomeViewModel, FragmentHomeBinding>() {
    private var scrolledDx = 0
    private var targetScrollDx = 0
    private var mAdapter: CourseListAdapter? = null
    private var data: JSONObject? = null

    override fun onAllViewCreated(savedInstanceState: Bundle?) {
        initView()
        initObserver()
        loadJsonLocally()
        requestForUserInfo()
    }

    companion object {
        private const val TAG = "HomeFragment"
        private const val REQUEST_CODE_ANTI_CHEAT = 1002  // 扫一扫
        private const val QR_DEBUG = "QR_DEBUG"
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_ANTI_CHEAT && resultCode == AppCompatActivity.RESULT_OK) {
            val result = data?.getStringExtra("result") ?: return
            startIntelligentSign(result)
        }
    }

    private suspend fun doPreSign(aid: String) {
        val analysisUrl = URL.getAnalysisPath(aid)
        val analysisResp = NetworkUtils.request(NetworkUtils.buildClientRequest(analysisUrl))
        val analysisBody = analysisResp.data?.body?.string() ?: ""
        val analysis2Code = analysisBody.substringAfter("code='+'").substringBefore("'")
        if (analysis2Code.isNotEmpty()) {
            NetworkUtils.request(NetworkUtils.buildClientRequest(URL.getAnalysis2Path(analysis2Code)))
        }
    }

    /**
     * 查询签到详情(含ifNeedVCode + otherId/clazzId/courseId)
     */
    private suspend fun querySignInfo(aid: String): SignInfo? {
        // 优先用 v2 API (信息最全)
        try {
            val url = "https://mobilelearn.chaoxing.com/v2/apis/active/getPPTActiveInfo?activeId=$aid&duid=&denc="
            val resp = NetworkUtils.request(NetworkUtils.buildClientRequest(url))
            val body = resp.data?.body?.string() ?: return null
            val json = body.safeParseToJson() ?: return null
            val data = json.getJSONObject("data") ?: return null
            return SignInfo(
                otherId = data.getString("otherId") ?: "",
                clazzId = data.getString("clazzId") ?: "",
                courseId = data.getString("courseId") ?: "",
                needVCode = data.getIntValue("ifNeedVCode") == 1
            )
        } catch (e: Exception) {
            DebugLogCollector.w(QR_DEBUG, "v2详情查询失败: ${e.message}, 降级signDetail")
        }
        // 降级: 用 signDetail API
        return try {
            val url = "https://mobilelearn.chaoxing.com/newsign/signDetail?activePrimaryId=$aid&type=1"
            val resp = NetworkUtils.request(NetworkUtils.buildClientRequest(url))
            val body = resp.data?.body?.string() ?: return null
            val json = body.safeParseToJson() ?: return null
            SignInfo(
                otherId = json.getString("otherId") ?: "",
                clazzId = json.getString("clazzId") ?: "",
                courseId = json.getString("courseId") ?: "",
                needVCode = json.getIntValue("ifNeedVCode") == 1
            )
        } catch (e: Exception) {
            DebugLogCollector.e(QR_DEBUG, "签到详情查询全部失败", e)
            null
        }
    }

    /** 签到详情数据类 */
    data class SignInfo(
        val otherId: String,
        val clazzId: String,
        val courseId: String,
        val needVCode: Boolean
    )

    private suspend fun proxySignForAccounts(
        aid: String, qrCodeId: String, location: String = "", enc: String? = null,
        preparedParams: Map<String, String> = emptyMap(),
        lat: String = "-1", lon: String = "-1", addr: String = ""
    ) {
        val signWith = requireActivity().getBySp("signWith")?.toBoolean() ?: false
        if (!signWith) {
            return
        }

        val manager = ProxySignManager(
            context = requireContext(),
            aid = aid,
            signType = Constants.SIGN.SCAN_QR,
            enc = enc,
            fullIdParams = qrCodeId,
            preparedParams = preparedParams,
            lat = lat, lon = lon, addr = addr
        )

        val progressJob = kotlinx.coroutines.Job()
        manager.onProgress = { current, total, name, success ->
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
                ToastUtils.show("代签($current/$total): ${if (success) "✅" else "❌"} $name")
            }
        }

        manager.loadUsers()
        if (manager.getUserCount() == 0) {
            return
        }

        val summary = manager.signForAll()
        DebugLogCollector.d(QR_DEBUG, "代签: 成功${summary.successCount} 失败${summary.failCount} 共${summary.totalCount}")
        withContext(Dispatchers.Main) {
            var msg = "代签: 成功${summary.successCount} 失败${summary.failCount} 共${summary.totalCount}"
            if (summary.obsoleteUsers.isNotEmpty()) {
                msg += "\n⚠ 以下账户需修复: ${summary.obsoleteUsers.joinToString(", ")}"
            }
            ToastUtils.show(msg)
        }
    }

    private fun initView() {
        fitToolbar()

        mAdapter = CourseListAdapter()
        targetScrollDx = dp2px(requireContext(), 76)
        binding?.rvCourseList?.apply {
            addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(
                    outRect: Rect,
                    view: View,
                    parent: RecyclerView,
                    state: RecyclerView.State
                ) {
                    val defaultPadding = dp2px(requireContext(), 15)
                    val toolbarHeight = binding?.appToolBar?.height ?: 0
                    if (parent.layoutManager?.getPosition(view) == 0) {
                        return outRect.set(
                            defaultPadding,
                            toolbarHeight + dp2px(requireContext(), 5),
                            defaultPadding,
                            defaultPadding
                        )
                    } else if (parent.layoutManager?.getPosition(view) == adapter?.itemCount?.minus(
                            1
                        )
                    ) {
                        return outRect.set(
                            defaultPadding,
                            0,
                            defaultPadding,
                            dp2px(requireContext(), 80)
                        )
                    }
                    return outRect.set(defaultPadding, 0, defaultPadding, defaultPadding)
                }
            })

            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    computeVerticalScrollOffset().let {
                        if (it > targetScrollDx) {
                            solidAppToolBar(255)
                            return
                        }
                        scrolledDx = it
                    }
                    solidAppToolBar(((scrolledDx.toFloat() / targetScrollDx.toFloat()) * 255).toInt())

                    Log.d(
                        "MainActivity",
                        "scrolledDx: $scrolledDx, dx: $dx, alpha: ${binding?.appToolBar?.background?.alpha}"
                    )
                }
            })

            onFlingListener = object : RecyclerView.OnFlingListener() {
                override fun onFling(velocityX: Int, velocityY: Int): Boolean {
                    Log.d("MainActivity", "onFling: $velocityY")
                    if (velocityY >= 1200) {
                        binding?.appToolBar?.background?.alpha = 255
                    }
                    return false
                }
            }
        }

        val uid = CacheUtils.cache["uid"]
        uid?.let {
            val options = RequestOptions().transform(
                CenterCrop(),
                RoundedCorners(dp2px(requireContext(), 5))
            )
            Glide.with(this)
                .load(URL.getAvtarImgPath(it))
                .apply(options)
                .into(binding!!.ivUserAvtar)
        }

        // 扫一扫 → 默认智能签到模式
        binding?.ivScanBtn?.setOnClickListener {
            val intent = Intent(requireContext(), ScanActivity::class.java)
            startActivityForResult(intent, REQUEST_CODE_ANTI_CHEAT)
        }

        binding?.ivDebugBtn?.setOnClickListener {
            SignReportDialog(requireContext()).show()
        }

    }


    /**
     * 构建代签预处理参数Map (从startIntelligentSign的本地变量)
     */
    private fun buildPreparedParams(
        captchaValidate: String?, courseId: String?, clazzId: String?
    ): Map<String, String> {
        val map = mutableMapOf<String, String>()
        captchaValidate?.let { map["validate"] = it }
        courseId?.let { map["courseId"] = it }
        clazzId?.let { map["clazzId"] = it }
        return map
    }

    /**
     * 智能签到: 先检查签到详情(ifNeedVCode),
     * 提前处理好验证码, 再一次性签到(避免enc被消耗后无法补救)
     */
    private fun startIntelligentSign(result: String) {
        val aid: String
        var enc: String? = null
        var fullIdParams: String? = null

        if (result.contains("SIGNIN:aid=")) {
            aid = result.substringAfter("SIGNIN:aid=").substringBefore("&")
            fullIdParams = result.substringAfter("SIGNIN:aid=")
        } else if (result.contains("id=")) {
            fullIdParams = result.substringAfter("id=")
            aid = fullIdParams.substringBefore("&")
            val encMatch = Regex("(?<=&enc=)[\\dA-Z]+").find(result)
            enc = encMatch?.value
        } else if (result.matches(Regex("\\d+"))) {
            aid = result
            fullIdParams = result
        } else {
            ToastUtils.show("无法识别签到二维码")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val uid = CacheUtils.cache["uid"] ?: ""
                DebugLogCollector.section("扫码签到 aid=$aid")

                // 1. 预签到 + 获取签到详情(合并为最少请求)
                doPreSign(aid)
                val info = querySignInfo(aid)
                val otherId = info?.otherId ?: ""
                val clazzId = info?.clazzId ?: ""
                val courseId = info?.courseId ?: ""
                val needVCode = info?.needVCode == true
                DebugLogCollector.d(QR_DEBUG, "签到类型otherId=$otherId, 需滑块验证码=$needVCode")

                // 2. 预处理: 验证码/定位
                var captchaValidate: String? = null
                var lat = "-1"; var lon = "-1"; var addr = ""

                // 2a. 验证码(需UI交互, 串行)
                if (needVCode) {
                    val cData = CaptchaHelper.getCaptchaImage(
                        courseId = courseId, classId = clazzId,
                        activeId = aid, uid = uid
                    )
                    if (cData != null) {
                        withContext(Dispatchers.Main) {
                            val dialog = com.cofbro.qian.view.dialog.SliderCaptchaDialog(
                                context = requireContext(), captchaData = cData,
                                activeId = aid, uid = uid,
                                courseId = courseId, classId = clazzId,
                                onResult = { captchaValidate = it }
                            )
                            dialog.show()
                        }
                        var w = 0; while (captchaValidate == null && w < 120) { delay(500); w++ }
                        if (captchaValidate == null) DebugLogCollector.w(QR_DEBUG, "验证码等待超时(60s)")
                    } else {
                        DebugLogCollector.w(QR_DEBUG, "获取验证码图片失败")
                    }
                }

                // 2b. 获取位置: GPS → 缓存 → 预设地址
                var nativeLat = 0.0; var nativeLon = 0.0; var nativeAddr = ""
                try {
                    NativeLocationUtils.getCurrentLocation(
                        requireContext(),
                        onSuccess = { l1, l2, a -> nativeLat = l1; nativeLon = l2; nativeAddr = a },
                        onError = {}
                    )
                } catch (_: Exception) {}
                if (nativeLat != 0.0 && nativeLon != 0.0) {
                    lat = nativeLat.toString(); lon = nativeLon.toString(); addr = nativeAddr
                } else {
                    CacheUtils.cache["default_Sign_latitude"]?.let { cacheLat ->
                        CacheUtils.cache["default_Sign_longitude"]?.let { cacheLon ->
                            lat = cacheLat; lon = cacheLon
                            addr = CacheUtils.cache["default_Sign_Location"] ?: ""
                        }
                    }
                    if (lat == "-1") {
                        val presets = PresetLocationManager.loadAll(requireContext())
                        if (presets.isNotEmpty()) {
                            val loc = presets[0]
                            val bdLatLon = NativeLocationUtils.wgs84ToBd09(loc.longitude, loc.latitude)
                            lat = bdLatLon.latitude.toString(); lon = bdLatLon.longitude.toString()
                            addr = loc.address.ifEmpty { loc.name }
                        }
                    }
                }

                // 3. 执行签到: 正确路径(单次GET) → 保底策略(errorLocation/validate)
                val signResult = executeSign(
                    aid = aid, uid = uid, otherId = otherId,
                    fullIdParams = fullIdParams, enc = enc,
                    lat = lat, lon = lon, addr = addr,
                    courseId = courseId, clazzId = clazzId,
                    captchaValidate = captchaValidate
                )
                DebugLogCollector.d(QR_DEBUG, "签到结果: ${signResult.message}")

                // 4. 处理签到结果
                val isSuccess = signResult.isSuccess() || signResult.isAlreadySigned
                withContext(Dispatchers.Main) {
                    when {
                        isSuccess && signResult.isAlreadySigned -> ToastUtils.show("已签到过了, 正在代签...")
                        isSuccess -> ToastUtils.show("智能签到成功!")
                        signResult.captchaToken != null -> ToastUtils.show("需要滑块验证码")
                        signResult.validateToken != null -> ToastUtils.show("需要拍照验证, 无法自动完成")
                        signResult.faceToken != null -> ToastUtils.show("需要人脸识别, 无法自动完成")
                        else -> ToastUtils.show("签到结果: ${signResult.message}")
                    }
                }

                // 5. 代签(本人签过了也触发, 代签用户可能没签)
                if (isSuccess) {
                    val preparedParams = buildPreparedParams(captchaValidate, courseId, clazzId)
                    proxySignForAccounts(aid, fullIdParams ?: aid, enc = enc,
                        preparedParams = preparedParams, lat = lat, lon = lon, addr = addr)
                }
            } catch (e: Exception) {
                DebugLogCollector.e(QR_DEBUG, "智能签到异常", e)
                withContext(Dispatchers.Main) { ToastUtils.show("智能签到失败: ${e.message}") }
            }
        }
    }

    /**
     * 执行签到: 正确路径前置, 保底策略后置
     *
     * QR签到正确路径: GET stuSignajax?activeId=fullIdParams&location=...&uid=...&enc=...
     * 非QR签到正确路径: POST stuSignajax (带坐标)
     *
     * 保底策略:
     * - errorLocation → 遍历预设地址
     * - validate_(验证码) → 用预获取validate重签
     */
    private suspend fun executeSign(
        aid: String, uid: String, otherId: String,
        fullIdParams: String?, enc: String?,
        lat: String, lon: String, addr: String,
        courseId: String, clazzId: String,
        captchaValidate: String?
    ): com.cofbro.qian.signer.SignResult {
        val isQrSign = otherId == Constants.SIGN.SCAN_QR

        if (isQrSign && !fullIdParams.isNullOrEmpty()) {
            // ===== QR签到: 正确路径(单次GET) =====
            val locationJson = """{"result":1,"latitude":$lat,"longitude":$lon,"address":"${addr.replace("\"", "\\\"")}"}"""
            val locationEncoded = java.net.URLEncoder.encode(locationJson, "UTF-8")
            val sb = StringBuilder("https://mobilelearn.chaoxing.com/pptSign/stuSignajax?activeId=$fullIdParams&location=$locationEncoded&uid=$uid")
            enc?.let { sb.append("&enc=$it") }
            captchaValidate?.let { sb.append("&validate=$it") }

            val resp = NetworkUtils.request(NetworkUtils.buildClientRequest(sb.toString()))
            val body = resp.data?.body?.string() ?: ""

            var result = com.cofbro.qian.signer.SignResult.fromBody(body)

            // 保底1: errorLocation → 遍历预设地址
            if (result.isLocationError) {
                val presets = PresetLocationManager.loadAll(requireContext())
                for ((i, loc) in presets.withIndex()) {
                    val bdLatLon = NativeLocationUtils.wgs84ToBd09(loc.longitude, loc.latitude)
                    val retryAddr = loc.address.ifEmpty { loc.name }
                    val retryLocJson = """{"result":1,"latitude":${bdLatLon.latitude},"longitude":${bdLatLon.longitude},"address":"${retryAddr.replace("\"", "\\\"")}"}"""
                    val retrySb = StringBuilder("https://mobilelearn.chaoxing.com/pptSign/stuSignajax?activeId=$fullIdParams&location=${java.net.URLEncoder.encode(retryLocJson, "UTF-8")}&uid=$uid")
                    enc?.let { retrySb.append("&enc=$it") }
                    captchaValidate?.let { retrySb.append("&validate=$it") }
                    val retryResp = NetworkUtils.request(NetworkUtils.buildClientRequest(retrySb.toString()))
                    val retryBody = retryResp.data?.body?.string() ?: ""
                    result = com.cofbro.qian.signer.SignResult.fromBody(retryBody)
                    if (result.isSuccess() || result.isAlreadySigned || result.isExpired || result.isEnded) break
                    if (result.validateToken != null || result.faceToken != null) break
                }
            }

            // 保底2: validate_(验证码) → 用预获取validate重签
            if (result.captchaToken != null && !captchaValidate.isNullOrEmpty()) {
                val retrySb = StringBuilder("https://mobilelearn.chaoxing.com/pptSign/stuSignajax?activeId=$fullIdParams&location=$locationEncoded&uid=$uid")
                enc?.let { retrySb.append("&enc=$it") }
                retrySb.append("&validate=$captchaValidate")
                val retryResp = NetworkUtils.request(NetworkUtils.buildClientRequest(retrySb.toString()))
                val retryBody = retryResp.data?.body?.string() ?: ""
                result = com.cofbro.qian.signer.SignResult.fromBody(retryBody)
            }

            return result
        }

        // ===== 非QR签到: 正确路径(POST) =====
        val resp = NetworkUtils.postSign(aid = aid, uid = uid,
            fid = CacheUtils.cache["fid"] ?: "", name = CacheUtils.cache["username"] ?: "",
            latitude = lat, longitude = lon, address = addr)
        var body = resp.data?.body?.string() ?: ""
        var result = com.cofbro.qian.signer.SignResult.fromBody(body)

        // 保底: errorLocation → 遍历预设地址
        if (result.isLocationError) {
            val presets = PresetLocationManager.loadAll(requireContext())
            for ((i, loc) in presets.withIndex()) {
                val bdLatLon = NativeLocationUtils.wgs84ToBd09(loc.longitude, loc.latitude)
                val locAddr = loc.address.ifEmpty { loc.name }
                val retryResp = NetworkUtils.postSign(aid = aid, uid = uid,
                    fid = CacheUtils.cache["fid"] ?: "", name = CacheUtils.cache["username"] ?: "",
                    latitude = bdLatLon.latitude.toString(), longitude = bdLatLon.longitude.toString(),
                    address = locAddr)
                body = retryResp.data?.body?.string() ?: ""
                result = com.cofbro.qian.signer.SignResult.fromBody(body)
                if (result.isSuccess() || result.isAlreadySigned || result.isExpired || result.isEnded) break
                if (result.validateToken != null || result.faceToken != null) break
            }
        }

        return result
    }

    private fun fitToolbar() {
        val params = binding?.appToolBar?.layoutParams
        params?.height = getStatusBarHeight(requireContext()) + dp2px(requireContext(), 45)
        binding?.appToolBar?.layoutParams = params
        solidAppToolBar(0)
    }

    private fun initObserver() {
        viewModel.loadCourseListLiveData.observe(this) {
            lifecycleScope.launch(Dispatchers.IO) {
                val s = it.data?.body?.string() ?: ""
                Downloader.download(requireContext(), Constants.RecycleJson.HOME_JSON_DATA, s)
                withContext(Dispatchers.Main) {
                    val jsonObject = s.safeParseToJson()
                    mAdapter?.setData(jsonObject)
                    data = jsonObject
                    binding?.rvCourseList?.adapter = mAdapter
                    binding?.rvCourseList?.layoutManager =
                        LinearLayoutManager(
                            requireContext(),
                            LinearLayoutManager.VERTICAL,
                            false
                        )
                    mAdapter?.setOnItemClickListener(object :
                        CourseListAdapter.AdapterListener {
                        override fun onItemClick(
                            courseId: String,
                            classId: String,
                            cpi: String,
                            courseName: String
                        ) {
                            toWrapperActivity(courseId, classId, cpi, courseName)
                        }
                    })
                }
            }
        }

        viewModel.signLiveData.observe(this) {
            lifecycleScope.launch(Dispatchers.IO) {
                val data = it.data?.body?.string()
                Log.d("MainActivity", "initObserver: $data")
            }
            ToastUtils.show("签到成功!")
        }

        viewModel.userInfoLiveData.observe(this) {
            lifecycleScope.launch(Dispatchers.IO) {
                val data = it.data?.body?.string() ?: ""
                withContext(Dispatchers.Main) {
                    val username = HtmlParser.parseToUsername(data)
                    CacheUtils.persistCookie(Constants.USER.USERNAME, username)
                }
            }
        }
    }

    private fun doNetwork() {
        viewModel.loadCourseList(URL.getAllCourseListPath())
    }

    private fun requestForUserInfo() {
        if (CacheUtils.cache[Constants.USER.USERNAME].isNullOrEmpty()) {
            viewModel.requestForUserInfo(URL.getUserInfo())
        }
    }

    private fun getDataItemCount(): Int {
        data?.getJSONArray(Constants.CourseList.CHANNEL_LIST)?.let { array ->
            return (array.size - 1).takeIf { it >= 0 } ?: 0
        }
        return 0
    }

    private fun solidAppToolBar(alpha: Int) {
        binding?.appToolBar?.background?.alpha = alpha
    }

    private fun toWrapperActivity(courseId: String, classId: String, cpi: String, courseName: String) {
        val intent = Intent(requireActivity(), WrapperActivity::class.java)
        intent.apply {
            putExtra("courseId", courseId)
            putExtra("classId", classId)
            putExtra("cpi", cpi)
            putExtra("courseName", courseName)
        }
        startActivity(intent)
    }

    private fun loadJsonLocally() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dataString = Downloader.acquire(requireContext(), Constants.RecycleJson.HOME_JSON_DATA)
            val firstLoad = CacheUtils.cache[Constants.DataLoad.FIRST_LOAD] ?: Constants.DataLoad.UNLOAD
            if (dataString.isEmpty() || firstLoad == Constants.DataLoad.UNLOAD) {
                doNetwork()
                CacheUtils.cache[Constants.DataLoad.FIRST_LOAD] = Constants.DataLoad.LOADED
                return@launch
            }
            val response = Response.Builder()
                .request(NetworkUtils.buildClientRequest(URL.getAllCourseListPath()))
                .header("refer", "")
                .protocol(Protocol.HTTP_1_1)
                .body(ResponseBody.create("application/json".toMediaTypeOrNull(), dataString))
                .code(200).message("OK").build()
            val baseResponse = BaseResponse<Response>()
            baseResponse.data = response
            baseResponse.dataState = DataState.STATE_INITIALIZE
            viewModel.loadCourseListLiveData.postValue(baseResponse)
        }
    }

}
