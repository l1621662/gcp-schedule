package edu.jxslu.schedule.data.jw

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正方无界面登录链路的回归钉（DESIGN §4.17）。
 *
 * HTML 片段取自 2026-09-23 对 `jwcjw.gcp.edu.cn/jwglxt/xtgl/login_slogin.html` 的实测抓取，
 * 只保留判定用得到的那几行；解析规则一旦跟真实页面脱节，这里会先红。
 */
class ZfJwSessionTest {

    /** 实测片段：csrf 是 `uuid,hex` 两段；密码 `mmsfjm=0` 表示不加密。 */
    private val loginHtml = """
        <input type="hidden" name="yzcskz" id="yzcskz" value= -1>
        <input type="hidden" name="mmsfjm" id="mmsfjm" value= 0>
        <input type="hidden" name="mmsrddcshkzfs" id="mmsrddcshkzfs" value= 0>
        <input type="hidden" name="dlsfbxyzm" id="dlsfbxyzm" value= 0>
        <form action="/jwglxt/xtgl/login_slogin.html" method="post">
        <input type="hidden" id="csrftoken" name="csrftoken" value="b924799d-b42e-42a5-b1d4-e044285b29f6,b924799db42e42a5b1d4e044285b29f6"/>
        <input type="hidden" id="language" name="language" value="zh_CN"/>
        <input type="text" name="yhm" id="yhm" value="">
        <input type="password" name="mm" id="hidMm" style="display:none">
        <div id="yzmDiv" class="form-group">
          <input name="yzm" type="text" id="yzm">
          <img id="yzmPic" name="yzmPic" width="108" height="34" />
        </div>
        </form>
    """.trimIndent()

    @Test
    fun extractCsrfToken_readsHiddenField() {
        assertEquals(
            "b924799d-b42e-42a5-b1d4-e044285b29f6,b924799db42e42a5b1d4e044285b29f6",
            ZfJwSession.extractCsrfToken(loginHtml),
        )
        assertNull("缺字段时必须报 null，交给上层给可读错误", ZfJwSession.extractCsrfToken("<html></html>"))
    }

    @Test
    fun extractHiddenValue_readsFlags() {
        assertEquals("0", ZfJwSession.extractHiddenValue(loginHtml, "mmsfjm"))
        assertEquals("-1", ZfJwSession.extractHiddenValue(loginHtml, "yzcskz"))
        assertNull(ZfJwSession.extractHiddenValue(loginHtml, "notThere"))
    }

    @Test
    fun captchaRequired_visibleDivMeansYes() {
        assertTrue("实测登录页 #yzmDiv 可见 + 必须填验证码", ZfJwSession.captchaRequired(loginHtml))
    }

    @Test
    fun captchaRequired_hiddenOrMissingMeansNo() {
        assertFalse(
            ZfJwSession.captchaRequired("""<div id="yzmDiv" style="display:none"><input id="yzm"></div>"""),
        )
        assertFalse(
            ZfJwSession.captchaRequired("""<div id="yzmDiv" style="display: none"><input id="yzm"></div>"""),
        )
        assertFalse("标签整体缺失时按不需要验证码处理", ZfJwSession.captchaRequired("<form></form>"))
    }

    @Test
    fun extractLoginError_prefersTipsAndFallsBackToKeywords() {
        assertEquals(
            "用户名或密码错误",
            ZfJwSession.extractLoginError("""<div id="tips"><span>用户名或密码错误</span></div>"""),
        )
        assertEquals("验证码错误", ZfJwSession.extractLoginError("<div>验证码错误，请重新输入</div>"))
        assertNull(ZfJwSession.extractLoginError("<html><body>正常页面</body></html>"))
    }

    @Test
    fun parseTermSelection_prefersSelectAndFallsBackToHidden() {
        val selectHtml = """
            <select id="xnm" name="xnm">
              <option value="2025">2025-2026</option>
              <option value="2026" selected>2026-2027</option>
            </select>
            <select id="xqm" name="xqm">
              <option value="3" selected>第一学期</option>
              <option value="12">第二学期</option>
            </select>
        """.trimIndent()
        assertEquals(Triple("2026", "3", "2026-2027-1"), ZfJwSession.parseTermSelection(selectHtml))

        val hiddenHtml = """
            <input type="hidden" name="xnm" value="2026">
            <input type="hidden" name="xqm" value="12">
        """.trimIndent()
        assertEquals(Triple("2026", "12", "2026-2027-2"), ZfJwSession.parseTermSelection(hiddenHtml))

        assertNull("抓不到就交给接口默认学期，不能瞎猜", ZfJwSession.parseTermSelection("<form></form>"))
        assertNull(ZfJwSession.parseTermSelection(""))
    }

    @Test
    fun buildTerm_mapsXqmCodes() {
        assertEquals("2026-2027-1", ZfJwSession.buildTerm("2026", "3"))
        assertEquals("2026-2027-2", ZfJwSession.buildTerm("2026", "12"))
        assertEquals("2026-2027-3", ZfJwSession.buildTerm("2026", "16"))
        assertNull(ZfJwSession.buildTerm("2026", "99"))
        assertNull(ZfJwSession.buildTerm("26", "3"))
        assertNull(ZfJwSession.buildTerm(null, "3"))
    }

    /** 会话复用：导出→导入必须能原样往返（后台检测就靠这一对函数）。 */
    @Test
    fun sessionCookie_roundTrips() {
        val url = "https://jwcjw.gcp.edu.cn/jwglxt/xtgl/login_slogin.html".toHttpUrl()
        val jar = ZfJwSession.InMemoryCookieJar()
        jar.saveFromResponse(
            url,
            listOf(
                Cookie.Builder().domain(url.host).path("/").name("JSESSIONID").value("ABC123").build(),
                Cookie.Builder().domain(url.host).path("/").name("route").value("a1b2").build(),
            ),
        )
        val dumped = jar.export(url.host)
        assertEquals("JSESSIONID=ABC123; route=a1b2", dumped)

        val restoredJar = ZfJwSession.InMemoryCookieJar()
        assertTrue(restoredJar.import(url.host, dumped))
        assertEquals(dumped, restoredJar.export(url.host))
        assertFalse("空串不算有会话", restoredJar.import(url.host, ""))
    }

    /** IPv4 优先：有 A 记录时 v4 排最前，且不丢弃 AAAA（校园 v6 在移动网是黑洞）。 */
    @Test
    fun ipv4First_prefersV4AndKeepsV6() {
        val v4a = InetAddress.getByName("117.40.44.51")
        val v4b = InetAddress.getByName("117.40.44.55")
        val v6 = InetAddress.getByName("2001:250:6c02:81::12")

        val ordered = ZfJwSession.ipv4First(listOf(v6, v4a, v4b))

        assertTrue("v4 必须排在最前", ordered.first() is Inet4Address)
        assertEquals("v4 有两条", 2, ordered.count { it is Inet4Address })
        assertEquals("v6 不丢弃，排在 v4 之后", 1, ordered.count { it is Inet6Address })
        assertTrue("末位是 v6", ordered.last() is Inet6Address)
    }

    @Test
    fun ipv4First_onlyV6_returnsAsIs() {
        val v6 = InetAddress.getByName("2001:250:6c02:81::12")
        val ordered = ZfJwSession.ipv4First(listOf(v6))
        assertEquals(1, ordered.size)
        assertTrue(ordered.first() is Inet6Address)
    }
}
