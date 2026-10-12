package com.cofbro.qian.wrapper.task

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import com.cofbro.hymvvmutils.base.BaseFragment
import com.cofbro.hymvvmutils.base.getBySp
import com.cofbro.qian.data.URL
import com.cofbro.qian.databinding.FragmentTaskBinding
import com.cofbro.qian.mapsetting.MapActivity
import com.cofbro.qian.photo.PhotoSignActivity
import com.cofbro.qian.scan.ScanActivity
import com.cofbro.qian.utils.AccountManager
import com.cofbro.qian.utils.NativeLocationUtils
import com.cofbro.qian.utils.CacheUtils
import com.cofbro.qian.utils.Constants
import com.cofbro.qian.utils.DebugLogCollector
import com.cofbro.qian.utils.HtmlParser
import com.cofbro.qian.utils.getJSONArrayExt
import com.cofbro.qian.utils.getStringExt
import com.cofbro.qian.utils.safeParseToJson
import com.cofbro.qian.utils.showSignResult
import com.cofbro.qian.view.dialog.CodingDialog
import com.cofbro.qian.view.dialog.FullScreenDialog
import com.cofbro.qian.view.dialog.GestureInputDialog
import com.cofbro.qian.wrapper.WrapperActivity
import com.cofbro.qian.wrapper.task.holder.Task
import com.cofbro.qian.wrapper.task.holder.TaskRunnableHolder
import com.hjq.toast.ToastUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * @author cofbro
 * 2023.10.6
 */
class TaskFragment : BaseFragment<TaskViewModel, FragmentTaskBinding>() {
    private var latitude = 0.0
    private var longitude = 0.0
    private var locationText = ""
    private var location = ""
    private var qrCodeId = ""

    // 签到的aid
    private var id = ""

    // 签到密码
    private var code = ""

    // 手势签到的dialog
    private var gestureInputDialog: GestureInputDialog? = null
    private var codeDialog: AlertDialog? = null
    private var activeId = ""
    private var signTypeData: JSONObject? = null
    private var preSignUrl = ""
    private var refreshing = false
    private val requestCode = 1
    private var activity: WrapperActivity? = null
    private var taskAdapter: TaskAdapter? = null
    private var loadingDialog: Dialog? = null

    override fun onAllViewCreated(savedInstanceState: Bundle?) {
        initArgs()
        initObserver()
        initView()
        doNetwork()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == AppCompatActivity.RESULT_OK) {
            val result = data?.getStringExtra("result")
            // SIGNIN:aid=402742574&source=15&Code=402742574&enc=548DF0246153AF088E756B59F33BF3F4
            // https://mobilelearn.chaoxing.com/widget/sign/e?id=2000072435046&c=2000072435046&enc=BC9662672047A2F2E4A607CC59762973&DB_STRATEGY=PRIMARY_KEY&STRATEGY_PARA=id
            // 这里的id包含url中的所有参数
            showLoadingView()
            val id = result?.substringAfter("id=")
            qrCodeId = id ?: ""
            lifecycleScope.launch(Dispatchers.IO) {
                analysisAndStartSign(id?.substringBefore("&") ?: "")
            }
        }
    }

    private fun initArgs() {
        activity = requireActivity() as WrapperActivity
    }

    private fun initView() {
        // rv
        binding?.rvSignTask?.apply {
            taskAdapter = TaskAdapter()
            taskAdapter?.setItemClickListener { itemData ->
                sign(itemData)
            }
            adapter = taskAdapter
            layoutManager =
                LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false)
        }

        // refresh
        binding?.rflSignTask?.apply {
            setOnRefreshListener {
                autoRefresh()
                refreshing = true
                doNetwork()
            }
        }
    }

    private fun initObserver() {
        // 活动列表
        viewModel.queryActiveTaskListLiveData.observe(this) {
            lifecycleScope.launch(Dispatchers.IO) {
                if (refreshing) {
                    refreshing = false
                    binding?.rflSignTask?.finishRefresh()
                }
                if (it.data == null) {
                    withContext(Dispatchers.Main) {
                        hideLoadingView()
                    }
                }
                val data = it.data?.body?.string()
                withContext(Dispatchers.Main) {
                    data?.safeParseToJson()?.let {
                        taskAdapter?.setData(it)
                    }
                }
            }
        }

        // 获取签到类型
        viewModel.signTypeLiveData.observe(this) {
            lifecycleScope.launch(Dispatchers.IO) {
                if (it.data == null) {
                    withContext(Dispatchers.Main) {
                        hideLoadingView()
                    }
                }
                val data = it.data?.body?.string()
                signTypeData = data?.safeParseToJson()
                // 签到类型获取后，开始签到
                realSign(signTypeData)
            }
        }

        viewModel.analysisLiveData.observe(this) {
            lifecycleScope.launch(Dispatchers.IO) {
                val data = it.data?.body?.string()
                val analysis2Code = data?.substringAfter("code='+'")?.substringBefore("'") ?: ""
                viewModel.analysis2(URL.getAnalysis2Path(analysis2Code))
                val courseId = activity?.courseId ?: ""
                val classId = activity?.classId ?: ""
                if (code.isNotEmpty()) {
                    signDirectly()
                } else if (qrCodeId.isNotEmpty()) {
                    signWithCamera(qrCodeId)
                } else {
                    getSignCode(id, classId, courseId)
                }
            }
        }

        viewModel.signCodeLiveData.observe(this) {
            lifecycleScope.launch(Dispatchers.IO) {
                if (it.data == null) {
                    withContext(Dispatchers.Main) {
                        hideLoadingView()
                    }
                } else {
                    val body = it.data?.body?.string() ?: ""
                    code = HtmlParser.parseToSignCode(body)
                    signDirectly()
                }
            }
        }

        // 签到
        viewModel.signLiveData.observe(this) {
            lifecycleScope.launch(Dispatchers.IO) {
                val data = it.data?.body?.string() ?: ""
                DebugLogCollector.d("Task", "签到响应: $data")
                withContext(Dispatchers.Main) {
                    responseUI(data)
                    // 开始代签
                    startSignTogether(data)
                }
            }
        }
    }

    private suspend fun startSignTogether(data: String) {
        val signWith = requireActivity().getBySp("signWith")?.toBoolean() ?: false
        if (signWith && (data.contains("success") || data.contains("签到成功"))) {
            showLoadingView()
            proxy()
        }
    }

    private suspend fun signDirectly() {
        DebugLogCollector.d("Task", "提交签到: aid=$id, signCode=$code")
        viewModel.preSign(preSignUrl)
        viewModel.request(URL.checkSignCodePath(id, code))
        // 签到
        signNormally(id, code)
    }

    private suspend fun responseUI(data: String) {
        hideLoadingView()
        data.showSignResult()
        // ★ 签到码输错时不关弹窗, 允许原地改码重试(手势码的重试由 responseGestureDialog 处理)
        if (data.contains("success") || data.contains("成功")) {
            codeDialog?.dismiss()
            codeDialog = null
        }
        responseGestureDialog(data)
    }

    private fun doNetwork() {
        activity?.let {
            queryAllActiveTask(it.courseId, it.classId, it.cpi)
        }
    }

    private fun queryAllActiveTask(courseId: String, classId: String, cpi: String) {
        // 查询所有活动
        val uid = CacheUtils.cache["uid"] ?: ""
        viewModel.queryActiveTaskList(URL.gatActiveTaskListPath(courseId, classId, uid, cpi))
    }

    private fun sign(itemData: JSONObject) {
        // aid
        val id = itemData.getStringExt(Constants.TaskList.ID)
        activeId = id
        // 2代表签到活动
        val type = itemData.getStringExt(Constants.TaskList.ACTIVE_TYPE)
        // 预签到地址
        preSignUrl = itemData.getStringExt(Constants.TaskList.PRE_SIGN_URL)
        // 2 -> 已结束
        val status = itemData.getStringExt(Constants.TaskList.STATUS)
        if (status == Constants.STATUS.CLOSE) {
            hideLoadingView()
            ToastUtils.show("签到已结束")
            return
        }
        showLoadingView()
        if (type == Constants.ACTIVITY.SIGN) {
            lifecycleScope.launch(Dispatchers.IO) {
                // 查询签到类型
                viewModel.findSignType(URL.getSignType(id))
            }
        }

    }

    private suspend fun realSign(itemData: JSONObject?) {
        val type = itemData?.getStringExt(Constants.SIGN.OTHER_ID)
        val ifPhoto = itemData?.getStringExt(Constants.SIGN.IF_PHOTO)
        id = itemData?.getStringExt(Constants.SIGN.ID) ?: ""
        DebugLogCollector.section("课件签到 aid=$id 类型=$type")
        // ★ 开始新活动的签到流程前, 清掉上一次残留的手势码/签到码/扫码结果
        // 下面 analysisLiveData 是用 code.isNotEmpty() 判断走哪条分支的, 不清空会拿旧码去签新活动
        code = ""
        qrCodeId = ""
        when (type) {
            // 二维码签到
            Constants.SIGN.SCAN_QR -> {
                lifecycleScope.launch(Dispatchers.IO) {
                    // 预签到
                    viewModel.preSign(preSignUrl)
                    // 跳转扫码界面
                    toScanActivity()
                }
            }
            // 普通签到
            Constants.SIGN.NORMAl -> {
                if (ifPhoto == Constants.SIGN.PHOTO) {
                    // 预签到
                    viewModel.preSign(preSignUrl)
                    // 照片签到
                    toPhotoSignActivity(id)
                } else {
                    viewModel.preSign(preSignUrl)
                    // 签到
                    signNormally(id)
                }
            }

            // 签到码签到，手势签到
            Constants.SIGN.SIGN_CODE, Constants.SIGN.GESTURE -> {
                withContext(Dispatchers.Main) {
                    hideLoadingView()
                    showChooseSignTypeDialog(id, type)
                }
            }

            // 定位签到
            Constants.SIGN.LOCATION -> {
                lifecycleScope.launch(Dispatchers.Main) {
                    toMapActivity(activeId, preSignUrl)
                }
            }
        }
    }

    private fun toMapActivity(aid: String, preUrl: String) {
        hideLoadingView()
        val intent = Intent(requireActivity(), MapActivity::class.java)
        intent.putExtra("aid", aid)
        intent.putExtra("preUrl", preUrl)
        /*
        跳转保存数据
         */
        intent.putExtra("courseName", activity?.courseName)
        intent.putExtra("courseId", activity?.courseId)
        intent.putExtra("classId", activity?.classId)
        intent.putExtra("cpi", activity?.cpi)

        startActivity(intent)
    }

    private fun toScanActivity() {
        hideLoadingView()
        val intent = Intent(requireActivity(), ScanActivity::class.java)
        startActivityForResult(intent, requestCode)
    }

    private fun signWithCamera(id: String?) {
        if (id.isNullOrEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            viewModel.preSign(preSignUrl)
            locate {
                lifecycleScope.launch(Dispatchers.IO) {
                    val address =
                        "{\"result\":1,\"latitude\":$latitude,\"longitude\":$longitude,\"address\":\"$locationText\"}"
                    location = URLEncoder.encode(address, "UTF-8")
                    viewModel.sign(URL.getSignWithCameraPath(id, location))
                }
            }
        }
    }

    private fun locate(onLocated: () -> Unit) {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            NativeLocationUtils.getCurrentLocation(
                requireContext(),
                onSuccess = { lat, lon, location ->
                    latitude = lat
                    longitude = lon
                    locationText = location
                    onLocated()
                },
                onError = {
                    lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        ToastUtils.show(it)
                    }
                }
            )
        }
    }

    private suspend fun signNormally(aid: String, signCode: String = "") {
        activity?.let {
            viewModel.sign(URL.getNormalSignPath(it.courseId, it.classId, aid, signCode))
        }
    }

    /**
     * 签到流程入口
     * @param aid activeId
     */
    private suspend fun analysisAndStartSign(aid: String) {
        viewModel.analysis(URL.getAnalysisPath(aid))
    }

    private suspend fun getSignCode(aid: String, classId: String, courseId: String) {
        viewModel.getSignCode(URL.getSignCodePath(aid, classId, courseId))
    }


    private fun toPhotoSignActivity(aid: String) {
        hideLoadingView()
        val intent = Intent(requireActivity(), PhotoSignActivity::class.java)
        intent.putExtra("aid", aid)
        startActivity(intent)
    }

    private fun showLoadingView() {
        if (loadingDialog == null) {
            loadingDialog = FullScreenDialog(requireContext())
            loadingDialog?.setCancelable(false)
            loadingDialog?.show()
        }
    }

    private fun hideLoadingView() {
        loadingDialog?.dismiss()
        loadingDialog = null
    }

    private fun showCodeDialog(id: String) {
        codeDialog = CodingDialog(requireContext()).apply {
            setCancelable(false)
            setPositiveClickListener {
                showLoadingView()
                DebugLogCollector.d("Task", "输入签到码: $it")
                lifecycleScope.launch(Dispatchers.IO) {
                    code = it
                    analysisAndStartSign(id)
                }
            }

            setNegativeClickListener {
                dismiss()
            }
            show()
        }
    }

    private suspend fun responseGestureDialog(data: String) {
        gestureInputDialog?.let {
            if (data.contains("success") || data.contains("成功")) {
                it.setState(true)
                delay(500)
                it.dismiss()
            } else {
                it.setState(false)
                delay(500)
                it.initData()
                it.setIsTouchAble(true)
            }
        }
    }

    private fun showGestureDialog(id: String) {
        gestureInputDialog = GestureInputDialog(requireContext()).apply {
            show()
            setInputEndListener { inputPwd ->
                this.setIsTouchAble(false)
                // 由于回调出来的密码是 Int数组，需遍历转成字符串
                var inputCode = ""
                inputPwd.forEach {
                    inputCode += it.toString()
                }
                DebugLogCollector.d("Task", "输入手势码: $inputCode")
                lifecycleScope.launch(Dispatchers.IO) {
                    code = inputCode
                    analysisAndStartSign(id)
                }
            }
        }
    }

    private suspend fun proxy() {
        val tasks = produceTasks()
        if (tasks.isEmpty()) {
            hideLoadingView()
            return
        }
        val totalCount = tasks.size
        var successCount = 0
        var failCount = 0
        lifecycleScope.launch(Dispatchers.IO) {
            val holder = TaskRunnableHolder(tasks)
            holder.submit()
            holder.blockingFetchingResult().forEachIndexed { index, result ->
                val body = result?.body ?: ""
                if (body.contains("成功") || body.contains("success")) {
                    successCount++
                } else {
                    failCount++
                }
                withContext(Dispatchers.Main) {
                    ToastUtils.show("代签进度: ${index + 1}/$totalCount")
                }
            }
            DebugLogCollector.d("Task", "代签完成: 成功$successCount 失败$failCount 共$totalCount")
            withContext(Dispatchers.Main) {
                hideLoadingView()
                ToastUtils.show("代签完成: 成功$successCount 失败$failCount 共$totalCount")
            }
        }
    }

    private fun produceTasks(): List<Task> {
        val data = loadAllSignUser()
        val list = arrayListOf<Task>()
        data.forEach {
            val task = Task(it as JSONObject)
            task.aid = id
            task.preSignUrl = preSignUrl
            task.classId = activity?.classId ?: ""
            task.courseId = activity?.courseId ?: ""
            task.code = code
            task.location = location
            task.qrCodeId = qrCodeId
            list.add(task)
        }
        return list
    }

    private fun loadAllSignUser(): JSONArray {
        val data = AccountManager.loadAllAccountData(requireContext())
        val cookieSignData = AccountManager.loadAllAccountData(
            requireContext(),
            Constants.RecycleJson.COOKIE_JSON_DATA
        )
        val allSignUsers = data.getJSONArrayExt(Constants.Account.USERS)
        cookieSignData.getJSONArrayExt(Constants.Account.USERS).forEach { user ->
            val timestamp =
                (user as? JSONObject)?.getStringExt(Constants.Account.TIME)?.toLong() ?: 0L
            if (System.currentTimeMillis() - timestamp <= 24 * 60 * 60 * 1000) {
                allSignUsers.add(user)
            }
        }
        return allSignUsers
    }

    private suspend fun showChooseSignTypeDialog(aid: String, type: String) {
        val fragment = SignTypeFragment().apply {
            setOnClickSignDirectlyListener {
                lifecycleScope.launch(Dispatchers.IO) {
                    analysisAndStartSign(aid)
                }
                dismiss()
            }

            setOnClickSignWithCodeListener {
                if (type == Constants.SIGN.GESTURE) {
                    showGestureDialog(aid)
                } else {
                    showCodeDialog(aid)
                }
                dismiss()
            }
        }
        fragment.show(requireActivity().supportFragmentManager, "SignTypeFragment")
    }
}