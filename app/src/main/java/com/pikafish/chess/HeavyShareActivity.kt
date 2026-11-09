package com.pikafish.chess

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import com.pikafish.chess.engine.EngineManager
import com.pikafish.chess.engine.SandboxOracle
import com.pikafish.chess.heavy.EngineLink
import com.pikafish.chess.heavy.EngineShareServer
import com.pikafish.chess.heavy.HeavyBeacon
import com.pikafish.chess.heavy.HeavyHttpServer
import com.pikafish.chess.heavy.HeavyStore
import com.pikafish.chess.heavy.PairingCode

/**
 * ★★★ 「配对码」接入辎重包：看码、抄码、对上就成。★★★
 *
 * <p>操作包首次进入时打开本页。操作只有一步：</p>
 * <ol>
 *   <li>在辎重包那边（电脑上双击辎重包程序，或另一台手机上的「黄昏象棋辎重包」）
 *       看到一个 6 位配对码；</li>
 *   <li>把码填到下面，点「开始配对」—— 自动找到、自动同步，完毕。</li>
 * </ol>
 *
 * <p>不涉及任何地址、端口、文件夹；两台设备只要连同一个 WiFi 即可。</p>
 */
class HeavyShareActivity : Activity() {

    companion object {
        /** 从首装引导页进入时置真，用于多给一句提示。 */
        const val EXTRA_FIRST_RUN = "heavy_first_run"
    }

    private val REQ_PICK = 2001
    private val mainHandler = Handler(Looper.getMainLooper())

    private var codeInput: EditText? = null
    private var pairButton: Button? = null
    /** ★★★ 「进入」按钮：与配对码绑死——配对成功前不出现、不可点，配对成功才亮出来。 */
    private var enterButton: Button? = null
    /** ★★★ 「导入本机辎重包 ZIP」按钮：仅当配对码填成保留码 000000 时才浮现。★★★ */
    private var importButton: Button? = null
    /** ★★★ 角色二选一：接收方（输码去配对）/ 发送方（出示码并借出本机引擎）。★★★ */
    private var roleGroup: RadioGroup? = null
    private var receiveRadio: RadioButton? = null
    private var sendRadio: RadioButton? = null
    /** 发送方的大号配对码展示（含提示）。 */
    private var hostCodeView: TextView? = null
    /** 当前是否作为「发送方」。 */
    @Volatile
    private var sender = false
    private var hostCode = ""
    private var hostStarted = false
    private var progressBar: ProgressBar? = null
    private var stateView: TextView? = null
    private var messageView: TextView? = null
    private var engineModeGroup: RadioGroup? = null
    private var localEngineRadio: RadioButton? = null
    private var remoteEngineRadio: RadioButton? = null
    /** 本次是否已配对成功（决定「进入」按钮是否亮出）。 */
    @Volatile
    private var paired = false
    /** 本次配对是否听到过「码不一致」的辎重包——用于决定收尾提示该说哪一句。 */
    private var heardMismatch = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(FixScale.fixedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    // ═════════════════ 界面 ═════════════════

    private fun buildUi() {
        val density = resources.displayMetrics.density
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF211005.toInt()) }

        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val pad = (20 * density).toInt()
        column.setPadding(pad, pad, pad, pad)

        val title = TextView(this).apply {
            text = "配对辎重包"
            setTextColor(0xFFE8C44A.toInt())
            textSize = 26f
            gravity = Gravity.CENTER
        }
        val hintView = TextView(this).apply {
            text = "在辎重包那边会显示一个 6 位配对码：\n" +
                "电脑上双击辎重包程序，或另一台手机上的「黄昏象棋辎重包」。\n" +
                "把那个码填到下面，点一下配对就成了。\n" +
                "两台设备连同一个 WiFi 即可，不用管别的。\n" +
                "\n若要直接用本机那份辎重包 ZIP：配对码填 000000，" +
                "即会出现「导入本机辎重包 ZIP」按钮。\n" +
                if (intent?.getBooleanExtra(EXTRA_FIRST_RUN, false) == true)
                    "\n（首次进入需要先配好辎重包，方可开弈。）" else ""
            setTextColor(0xFFCCBB88.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, (16 * density).toInt(), 0, 0)
        }

        codeInput = EditText(this).apply {
            hint = "123456"
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            textSize = 30f
            setTextColor(0xFFE8C44A.toInt())
            setHintTextColor(0x66CCBB88.toInt())
            setPadding(0, (14 * density).toInt(), 0, (14 * density).toInt())
        }

        val codeRow = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (16 * density).toInt() }
        column.addView(title)
        column.addView(hintView)

        // ★★★ 角色二选一（互斥）：接收方＝输码去配对；发送方＝出示码并把本机引擎借出去。★★★
        column.addView(TextView(this).apply {
            text = "本机角色？（想给别的手机借引擎就选「发送方」）"
            setTextColor(0xFFCCBB88.toInt()); textSize = 15f; gravity = Gravity.CENTER
            setPadding(0, (14 * density).toInt(), 0, 0)
        })
        roleGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            gravity = Gravity.CENTER
        }
        receiveRadio = RadioButton(this).apply {
            text = "接收方（输码）"; textSize = 15f; setTextColor(0xFFE8C44A.toInt()); id = View.generateViewId()
        }
        sendRadio = RadioButton(this).apply {
            text = "发送方（出码）"; textSize = 15f; setTextColor(0xFFE8C44A.toInt()); id = View.generateViewId()
        }
        roleGroup?.addView(receiveRadio)
        roleGroup?.addView(sendRadio)
        receiveRadio?.isChecked = true
        column.addView(roleGroup)

        column.addView(codeInput, codeRow)

        // ★ 发送方的大号配对码（仅发送方出现）。
        hostCodeView = TextView(this).apply {
            text = ""
            setTextColor(0xFFFFE27A.toInt()); textSize = 34f; gravity = Gravity.CENTER
            letterSpacing = 0.12f; visibility = View.GONE
            setPadding(0, (14 * density).toInt(), 0, (8 * density).toInt())
        }
        column.addView(hostCodeView)

        roleGroup?.setOnCheckedChangeListener { _, _ -> applyRole() }
        // ★★★ 边填边判：填成保留码 000000，即亮出「导入本机辎重包 ZIP」；否则只留「开始配对」。★★★
        codeInput?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { updateCodeUi() }
        })

        // ★ 引擎档位：本地引擎 / 异地引擎，由用户自选，配对后立即生效。
        val modeHint = TextView(this).apply {
            text = "算棋交给谁？"
            setTextColor(0xFFCCBB88.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, (18 * density).toInt(), 0, 0)
        }
        column.addView(modeHint)

        engineModeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        localEngineRadio = RadioButton(this).apply {
            text = "本地引擎（本机自算，手机略热）"
            textSize = 15f
            setTextColor(0xFFE8C44A.toInt())
            id = View.generateViewId()
        }
        remoteEngineRadio = RadioButton(this).apply {
            text = "异地引擎（辎重包端代算，手机不发烫）"
            textSize = 15f
            setTextColor(0xFFE8C44A.toInt())
            id = View.generateViewId()
        }
        engineModeGroup?.addView(localEngineRadio)
        engineModeGroup?.addView(remoteEngineRadio)
        // 默认跟随上次选择：无记录时默认本地引擎（老用户行为不变）。
        if (EngineLink.mode(this) == EngineLink.MODE_REMOTE) {
            remoteEngineRadio?.isChecked = true
        } else {
            localEngineRadio?.isChecked = true
        }
        column.addView(engineModeGroup)
        // ★★★ 勾选即生效：本地/异地一勾，立刻写盘并让询招引擎按新档位重拉——
        //   不再"必须重新配对一次才改得动"，杜绝"我明明选了本地，它却顽固走异地"。★★★
        engineModeGroup?.setOnCheckedChangeListener { _, _ ->
            EngineLink.setMode(this, selectedEngineMode())
            SandboxOracle.shutdown()
            refreshState()
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            visibility = ProgressBar.GONE
        }
        column.addView(progressBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (14 * density).toInt()
        ).apply { topMargin = (14 * density).toInt() })

        messageView = TextView(this).apply {
            setTextColor(0xFFFFD37A.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        column.addView(messageView)

        stateView = TextView(this).apply {
            setTextColor(0xFF997733.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, (14 * density).toInt(), 0, 0)
        }
        column.addView(stateView)

        fun addButton(label: String, onClick: () -> Unit): Button {
            val button = Button(this).apply {
                text = label
                textSize = 16f
                setOnClickListener { onClick() }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (14 * density).toInt()
            column.addView(button, lp)
            return button
        }

        pairButton = addButton("开始配对并同步", ::startPairing)
        // ★★★ 只有配对码填成保留码 000000 才会出现；导入成功后，再亮出「进入」。★★★
        importButton = addButton("导入本机辎重包 ZIP…", ::pickFile).apply { visibility = View.GONE }
        // ★★★ 「进入」与配对码绑死：配对成功之前一律不出现；配对成功后才亮出来，点它进主界面。★★★
        enterButton = addButton("进入") { finish() }.apply { visibility = View.GONE }
        updateCodeUi()

        scroll.addView(column)
        root.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)
    }

    // ═════════════════ 状态 ═════════════════

    private fun refreshState() {
        // 如实、直白地写清两件事，让人一眼看清「配没配上、算棋在本地还是跨设备」：
        //  - 当前辎重包：远程直读（跨设备）还是本机文件；
        //  - 算棋方式：本地引擎（本机自算）还是异地引擎（辎重包端代算）。
        stateView?.text = "当前辎重包：" + HeavyStore.describe(this) +
            "\n算棋方式：" + EngineLink.describe(this)
    }

    /** 当前界面选中的引擎档位。 */
    private fun selectedEngineMode(): String =
        if (remoteEngineRadio?.isChecked == true) EngineLink.MODE_REMOTE else EngineLink.MODE_LOCAL

    private fun setMessage(text: String) {
        mainHandler.post { messageView?.text = text }
    }

    private fun setBusy(busy: Boolean, indeterminate: Boolean = true) {
        mainHandler.post {
            pairButton?.isEnabled = !busy
            val bar = progressBar ?: return@post
            bar.visibility = if (busy || bar.progress > 0) ProgressBar.VISIBLE else ProgressBar.GONE
            bar.isIndeterminate = busy && indeterminate
        }
    }

    // ═════════════════ 配对 ═════════════════

    private fun startPairing() {
        val code = PairingCode.normalize(codeInput?.text?.toString())
        if (!PairingCode.valid(code)) {
            setMessage("请填 6 位配对码（就是辎重包那边显示的那个数字）")
            return
        }
        // ★ 000000 是「导入本机辎重包 ZIP」的保留码，不拿来配对——一码归一码，绝不混淆。
        if (PairingCode.isLocalImportCode(code)) {
            setMessage("000000 是导入本机辎重包的保留码，请点『导入本机辎重包 ZIP…』。")
            return
        }
        heardMismatch = false
        setBusy(true)
        setMessage("正在同一网络下寻找配对码 ${PairingCode.spaced(code)} 的辎重包…")
        HeavyBeacon.discover(code, object : HeavyBeacon.Listener {
            override fun onResult(peer: HeavyBeacon.Peer?) {
                if (peer == null) {
                    setBusy(false)
                    // 若刚听过「码不一致」，就保留那条更有用的提示，别再被这句笼统的话盖掉。
                    if (!heardMismatch)
                        setMessage("没找到这个码的辎重包。请确认：辎重包已打开、两台设备连同一个 WiFi，且只开着一个辎重包窗口，然后重试。")
                } else {
                    heardMismatch = false
                    beginSync(peer)
                }
            }

            override fun onError(message: String) {
                setBusy(false)
                setMessage("查找失败：$message")
            }

            override fun onMismatch(heardCode: String, host: String) {
                heardMismatch = true
                // ★ 把听到的真实码直接摊给用户看——多半是开了两个辎重包窗口，抄了另一个的码。
                setMessage(
                    "找到了一台辎重包（$host），但它的配对码是 ${PairingCode.spaced(heardCode)}，" +
                        "与您填的 ${PairingCode.spaced(code)} 不一致。\n" +
                        "请核对：是不是看错了窗口（有多个辎重包在跑？），或连到了别的设备。"
                )
            }
        })
    }

    /**
     * 找到后：
     *  - 「本地引擎」：把辎重包整份同步到本机（之后完全离线可用、运行最流畅，但占本机空间）。
     *  - 「异地引擎」：辎重包留在对方设备，本机「远程直读」只按需拉片段，不整份下载；
     *    同时记下对方的引擎地址，之后算棋整个由对方那台完成 —— 本机不加载权重、不运算，不发烫。
     */
    private fun beginSync(peer: HeavyBeacon.Peer) {
        var mode = selectedEngineMode()
        // ★ 对方辎重包明确提供了引擎端口 → 自动切「异地引擎」，
        //   不再让"配对成功却还在用本机元月1号皮卡鱼"这种事发生。
        if (peer.enginePort > 0 && mode != EngineLink.MODE_REMOTE) {
            mode = EngineLink.MODE_REMOTE
            runOnUiThread { remoteEngineRadio?.isChecked = true }
        }
        val engineEndpoint = peer.engineEndpoint()

        // 记住档位与对方引擎地址（异地引擎必需；本地引擎也一并记住，方便以后切换）。
        EngineLink.setMode(this, mode)
        if (peer.enginePort > 0) {
            EngineLink.setEndpoint(this, peer.host, peer.enginePort)
        }
        // ★ 切到异地：立刻停掉本机红黑本地引擎，别让它们在后台预热/预想发热。
        if (mode == EngineLink.MODE_REMOTE) {
            try { EngineManager.active?.shutdownLocalEnginesForRemote() } catch (_: Throwable) {}
        }
        // 档位变了 → 让询招引擎下次重新按新档位拉起（旧实例可能是本地/异地另一档）。
        SandboxOracle.shutdown()

        if (mode == EngineLink.MODE_REMOTE && engineEndpoint != null) {
            beginRemoteAttach(peer, engineEndpoint)
        } else {
            beginDownload(peer)
        }
    }

    /** 异地引擎：远程直读辎重包（不整份下载），并记下对方引擎地址。 */
    private fun beginRemoteAttach(peer: HeavyBeacon.Peer, engineEndpoint: String) {
        setMessage("已找到！正在接通异地引擎（辎重包留在对方设备，本机不下载大件）…")
        setBusy(true)
        Thread {
            try {
                HeavyStore.attachRemote(this, peer.packUrl())
                mainHandler.post {
                    setBusy(false)
                    progressBar?.visibility = ProgressBar.GONE
                    refreshState()
                    showEnter()
                    setMessage("配对成功！异地引擎已接通（$engineEndpoint）。\n本机只负责界面与触摸，算棋由对方完成，不发烫。")
                }
            } catch (e: Exception) {
                mainHandler.post {
                    setBusy(false)
                    setMessage("远程直读没成（${e.message}），改为下载到本机…")
                    beginDownload(peer)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** 本地引擎：整份同步到本机（之后完全离线可用、运行最流畅）。 */
    private fun beginDownload(peer: HeavyBeacon.Peer) {
        setMessage("正在同步辎重包到本机…（文件较大，请稍候）")
        setBusy(true, indeterminate = false)
        Thread {
            try {
                HeavyStore.importUrl(this, peer.packUrl()) { done, total ->
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                        mainHandler.post {
                            progressBar?.isIndeterminate = false
                            progressBar?.progress = pct
                            messageView?.text = "正在同步辎重包… $pct%"
                        }
                    }
                }
                mainHandler.post {
                    progressBar?.progress = 100
                    setBusy(false)
                    refreshState()
                    showEnter()
                    setMessage("配对成功！辎重包已就绪，可以开始对弈了。")
                }
            } catch (e: Exception) {
                mainHandler.post {
                    setBusy(false)
                    progressBar?.visibility = ProgressBar.GONE
                    setMessage("同步失败：${e.message}")
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * ★★★ 配对成功：亮出「进入」按钮。★★★
     *
     * <p>这是"配对码与进入绑死"的关键一环——配对成功之前「进入」一律不出现；
     * 只有真正对上了码（远程接通 或 整份同步完成）才亮出来。此后点「进入」即进主界面。
     * 「导入本机辎重包文件」这条旁路已按用户要求整体移除：一切只走配对码。</p>
     */
    private fun showEnter() {
        paired = true
        mainHandler.post { enterButton?.visibility = View.VISIBLE }
    }

    /**
     * ★★★ 按配对码内容切换按钮可见性：★★★
     *   - 填成保留码 000000：「开始配对并同步」隐藏，只浮现「导入本机辎重包 ZIP…」；
     *   - 其它（空码 / 普通码）：「导入…」隐藏，只留「开始配对并同步」。
     * 配对与导入两条路永不并存，一码归一码。
     */
    private fun updateCodeUi() {
        if (sender) {
            // 发送方：只管出码、借引擎、共享大件；不输码、不导入、不配对。
            codeInput?.visibility = View.GONE
            pairButton?.visibility = View.GONE
            importButton?.visibility = View.GONE
            hostCodeView?.visibility = View.VISIBLE
            return
        }
        codeInput?.visibility = View.VISIBLE
        hostCodeView?.visibility = View.GONE
        val isLocalImport = PairingCode.isLocalImportCode(
            PairingCode.normalize(codeInput?.text?.toString())
        )
        pairButton?.visibility = if (isLocalImport) View.GONE else View.VISIBLE
        importButton?.visibility = if (isLocalImport) View.VISIBLE else View.GONE
    }

    /**
     * ★★★ 角色切换：接收方（输码去配对）／发送方（出示码 + 借出本机引擎）。★★★
     * 发送方会开启「大件共享 + 借出引擎 + 配对广播」并出示一枚码；本机照常自行走棋。
     */
    private fun applyRole() {
        val wantSend = sendRadio?.isChecked == true
        if (wantSend == sender) return
        sender = wantSend
        if (sender) {
            // ★ 发送方：直接进「发送端控制台」——出码 + 借引擎 + 连接状态 + 小棋盘 + 思考细节
            //   + 切换引擎 / 加载开局库，全部摊在明面上。
            try {
                startActivity(Intent(this, SenderActivity::class.java))
            } catch (e: Exception) {
                setMessage("发送端启动失败：${e.message}")
            }
        }
        updateCodeUi()
    }


    // ═════════════════ 本机辎重包 ZIP 导入（仅保留码 000000 触发）═════════════════

    private fun pickFile() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, REQ_PICK)
        } catch (e: Exception) {
            setMessage("无法打开文件选择器：${e.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            setMessage("已取消")
            return
        }
        setMessage("正在导入辎重包…（文件较大，请稍候）")
        val captured = uri
        Thread {
            try {
                HeavyStore.importUri(this, captured)
                mainHandler.post {
                    refreshState()
                    setMessage("辎重包就绪：${HeavyStore.describe(this)}")
                    showEnter()
                }
            } catch (e: Exception) {
                mainHandler.post { setMessage("导入失败：${e.message}") }
            }
        }.apply { isDaemon = true }.start()
    }
}
