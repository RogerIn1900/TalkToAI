package com.example.talktoai

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.tencent.kuikly.core.render.android.IKuiklyRenderExport
import com.tencent.kuikly.core.render.android.adapter.KuiklyRenderAdapterManager
import com.tencent.kuikly.core.render.android.css.ktx.toMap
import com.tencent.kuikly.core.render.android.expand.KuiklyRenderViewBaseDelegatorDelegate
import com.tencent.kuikly.core.render.android.expand.KuiklyRenderViewBaseDelegator
import com.example.talktoai.adapter.KRColorParserAdapter
import com.example.talktoai.adapter.KRFontAdapter
import com.example.talktoai.adapter.KRImageAdapter
import com.example.talktoai.adapter.KRLogAdapter
import com.example.talktoai.adapter.KRRouterAdapter
import com.example.talktoai.adapter.KRThreadAdapter
import com.example.talktoai.adapter.KRUncaughtExceptionHandlerAdapter
import com.example.talktoai.module.KRBridgeModule
import com.example.talktoai.module.KRShareModule
import org.json.JSONObject
import com.example.talktoai.chat.AttachmentStore
import com.example.talktoai.chat.ChatAttachment
import com.example.talktoai.chart.TalkDataChartView
import com.example.talktoai.chart.TalkMarketChartView
import com.talktoai.marketui.registerMarketUiViews
import java.util.concurrent.Executors

class KuiklyRenderActivity : AppCompatActivity(), KuiklyRenderViewBaseDelegatorDelegate {

    private lateinit var hrContainerView: ViewGroup
    private lateinit var loadingView: View
    private lateinit var errorView: View

    private val kuiklyRenderViewDelegator = KuiklyRenderViewBaseDelegator(this)
    private val attachmentExecutor = Executors.newSingleThreadExecutor()
    private var attachmentCallback: ((Result<ChatAttachment>) -> Unit)? = null
    private val attachmentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val callback = attachmentCallback.also { attachmentCallback = null } ?: return@registerForActivityResult
        if (uri == null) {
            callback(Result.failure(IllegalStateException("ATTACHMENT_PICK_CANCELLED")))
            return@registerForActivityResult
        }
        attachmentExecutor.execute {
            val result = runCatching { AttachmentStore(applicationContext).import(uri) }
            runOnUiThread { callback(result) }
        }
    }

    private val pageName: String
        get() {
            val pn = intent.getStringExtra(KEY_PAGE_NAME) ?: ""
            return if (pn.isNotEmpty()) {
                return pn
            } else {
                "talk_to_ai"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(R.layout.activity_hr)
        setupImmersiveMode()
        hrContainerView = findViewById(R.id.hr_container)
        loadingView = findViewById(R.id.hr_loading)
        errorView = findViewById(R.id.hr_error)
        kuiklyRenderViewDelegator.onAttach(hrContainerView, "", pageName, createPageData())
    }

    override fun onDestroy() {
        attachmentCallback = null
        attachmentExecutor.shutdownNow()
        super.onDestroy()
        kuiklyRenderViewDelegator.onDetach()
    }

    fun pickAttachment(callback: (Result<ChatAttachment>) -> Unit) {
        if (attachmentCallback != null) {
            callback(Result.failure(IllegalStateException("ATTACHMENT_PICK_IN_PROGRESS")))
            return
        }
        attachmentCallback = callback
        android.app.AlertDialog.Builder(this)
            .setTitle("添加附件")
            .setItems(arrayOf("图片", "CSV / TXT 文件")) { _, selected ->
                attachmentPicker.launch(if (selected == 0) arrayOf("image/*") else arrayOf("text/csv", "text/plain"))
            }
            .setNegativeButton("返回") { _, _ ->
                attachmentCallback = null
                callback(Result.failure(IllegalStateException("ATTACHMENT_PICK_CANCELLED")))
            }
            .setOnCancelListener {
                attachmentCallback = null
                callback(Result.failure(IllegalStateException("ATTACHMENT_PICK_CANCELLED")))
            }
            .show()
    }

    override fun onPause() {
        super.onPause()
        kuiklyRenderViewDelegator.onPause()
    }

    override fun onResume() {
        super.onResume()
        kuiklyRenderViewDelegator.onResume()
    }

    override fun registerExternalModule(kuiklyRenderExport: IKuiklyRenderExport) {
        super.registerExternalModule(kuiklyRenderExport)
        with(kuiklyRenderExport) {
            moduleExport(KRBridgeModule.MODULE_NAME) {
                KRBridgeModule()
            }
            moduleExport(KRShareModule.MODULE_NAME) {
                KRShareModule()
            }
        }
    }

    override fun registerExternalRenderView(kuiklyRenderExport: IKuiklyRenderExport) {
        super.registerExternalRenderView(kuiklyRenderExport)
        with(kuiklyRenderExport) {
            registerMarketUiViews(this)
            renderViewExport("TalkDataChart", { context -> TalkDataChartView(context) })
            renderViewExport("TalkMarketChart", { context -> TalkMarketChartView(context) })
        }
    }

    private fun createPageData(): Map<String, Any> {
        val param = argsToMap()
        param["appId"] = 1
        param["isNightMode"] = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        param["fontScale"] = resources.configuration.fontScale
        return param
    }

    private fun argsToMap(): MutableMap<String, Any> {
        val jsonStr = intent.getStringExtra(KEY_PAGE_DATA) ?: return mutableMapOf()
        return JSONObject(jsonStr).toMap()
    }

    private fun setupImmersiveMode() {
        window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            val isNight = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
            // Edge-to-edge layout prevents adjustResize from resizing Kuikly's root view on IME display.
            // Keep content inside system bars so the composer is always pushed above the keyboard.
            statusBarColor = if (isNight) Color.BLACK else Color.WHITE
            decorView.systemUiVisibility = if (isNight) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            navigationBarColor = if (isNight) Color.BLACK else Color.WHITE
        }

    }

    companion object {

        private const val KEY_PAGE_NAME = "pageName"
        private const val KEY_PAGE_DATA = "pageData"

        init {
            initKuiklyAdapter()
        }

        fun start(context: Context, pageName: String, pageData: JSONObject) {
            val starter = Intent(context, KuiklyRenderActivity::class.java)
            starter.putExtra(KEY_PAGE_NAME, pageName)
            starter.putExtra(KEY_PAGE_DATA, pageData.toString())
            context.startActivity(starter)
        }

        private fun initKuiklyAdapter() {
            with(KuiklyRenderAdapterManager) {
                krImageAdapter = KRImageAdapter(KRApplication.application)
                krLogAdapter = KRLogAdapter
                krUncaughtExceptionHandlerAdapter = KRUncaughtExceptionHandlerAdapter
                krFontAdapter = KRFontAdapter
                krColorParseAdapter = KRColorParserAdapter(KRApplication.application)
                krRouterAdapter = KRRouterAdapter
                krThreadAdapter = KRThreadAdapter()
            }
        }
    }
}
