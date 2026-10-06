package com.myra.assistant.ai

import android.content.Context
import com.myra.assistant.util.MyraMemory

/**
 * Trading Class mode: MYRA becomes Fazil's voice trading teacher
 * (Binance spot/futures), like a real class.
 *
 * - She teaches ONE point at a time, then asks "samajh aaya? koi sawal hai?"
 * - He asks questions mid-lesson; she answers fully, then continues.
 * - Practicals: HE operates TradingView; she gives ONE micro-step at a time
 *   and verifies via the accessibility screen read (get_screen_elements)
 *   where feasible, else ask-and-confirm with a self-check rule.
 * - Progress (lesson/point) persists in SharedPreferences so a restart
 *   resumes where he left off.
 *
 * The model drives this through the "trading_class" tool (see ToolHandler);
 * [handleAction] returns the full tool-result string including the exact
 * chunk to teach right now.
 */
object TradingClassState {
    private const val PREFS = "trading_class"
    private var appContext: Context? = null
    private var loaded = false

    var active = false
    var lesson = 0 // 0 = never started, else 1..6
    var point = 0 // 0..teachPoints.size ; == size means practical phase
    var pstep = 0 // practical micro-step index

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        if (!loaded) {
            loaded = true
            load()
        }
    }

    private fun prefs() =
        appContext!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load() {
        try {
            val p = prefs()
            active = p.getBoolean("active", false)
            lesson = p.getInt("lesson", 0)
            point = p.getInt("point", 0)
            pstep = p.getInt("pstep", 0)
        } catch (_: Exception) {
        }
    }

    fun save() {
        try {
            prefs().edit()
                .putBoolean("active", active)
                .putInt("lesson", lesson)
                .putInt("point", point)
                .putInt("pstep", pstep)
                .apply()
        } catch (_: Exception) {
        }
    }
}

data class PracticalStep(val text: String, val verifyHint: String)

data class ClassLesson(
    val title: String,
    val intro: String,
    val points: List<String>,
    val practical: List<PracticalStep>
)

object TradingClassPrompt {

    val lessons: List<ClassLesson> = listOf(
        ClassLesson(
            title = "Support, Resistance aur Trend — pro ki nazar se",
            intro = "Lesson 1 me hum support, resistance aur trend ko pro ki nazar se " +
                    "dekhenge — sirf pehchanna nahi, unhe istemal karna seekhenge.",
            points = listOf(
                "Support buyers ka ilaqa hai — jahan price gir kar baar baar rukti hai. " +
                        "Jitni zyada baar price wahan se palte, utni mazboot deewar. Aur suno, " +
                        "jab support toot jati hai to wahi line upar se resistance ban jati hai — " +
                        "ise kehte hain role reversal, aur ye pro traders ka pasandeeda setup hai.",
                "Resistance sellers ka ilaqa hai — jahan price charh kar baar baar rukti hai. " +
                        "Gol numbers jaise 90,000 ya 100,000 psychological resistance hote hain, wahan " +
                        "bheer hoti hai. Pro ka usool yaad rakho: resistance ke bilkul paas khareedna " +
                        "ghalti hai — wahan bikne wale khare hote hain.",
                "Trend structure: agar har uchhaal pichle se uncha aur har girawat pichli se " +
                        "unchi ho — higher highs, higher lows — to uptrend hai, buyers ka control hai. " +
                        "Iska ulta — lower highs, lower lows — downtrend hai. Trendline hamesha kam az " +
                        "kam 2 touches se kheecho; ek touch wali line sirf andaza hai.",
                "Breakout aur fakeout ka farq: asli breakout me price deewar ke paar CLOSE hoti hai " +
                        "AUR volume uchhalta hai. Bina volume ka breakout aksar jhoota hota hai. Pro " +
                        "entry breakout par nahi, uske baad wale retest par lete hain — jab price wapas " +
                        "aa kar tooti deewar ko chhoo kar palte."
            ),
            practical = listOf(
                PracticalStep(
                    "TradingView app kholo.",
                    "get_screen_elements me TradingView ya chart screen nazar aani chahiye."
                ),
                PracticalStep(
                    "Upar search me BTCUSDT likho aur Binance ka BTC/USDT chart kholo.",
                    "Screen par 'BTCUSDT' ya 'BTC/USDT' text nazar aana chahiye."
                ),
                PracticalStep(
                    "Timeframe Daily karo.",
                    "Screen par '1D' ya 'Daily' nazar aana chahiye."
                ),
                PracticalStep(
                    "Drawing toolbar se Horizontal Line (seedhi lakeer) ka tool chuno.",
                    "Drawing menu ya line tool select nazar aana chahiye."
                ),
                PracticalStep(
                    "Pichle 3 mahine me jahan price 2 ya zyada baar ruk kar upar gayi — wahan 2 support lines lagao.",
                    "Line ki sahi jagah screen se verify nahi ho sakti — us se poocho 'lag gayin?' aur yaad dilao: har line kam az kam 2 touches wali jagah par honi chahiye."
                ),
                PracticalStep(
                    "Jahan price 2 ya zyada baar ruk kar neeche aayi — wahan 2 resistance lines lagao.",
                    "Us se poocho 'ho gaya?' — self-check: kya dono lines 2+ touches wali jagah par hain?"
                ),
                PracticalStep(
                    "Trendline tool se lagataar unche lows (higher lows) ko jor kar ek line kheecho — ye tumhara uptrend guide hai.",
                    "Us se poocho 'line lag gayi?' — self-check: kya line kam az kam 2 higher lows ko chhooti hai?"
                )
            )
        ),
        ClassLesson(
            title = "Indicators jo pros use karte hain",
            intro = "Lesson 2: EMA 50/200, RSI 14 aur Volume. Yaad rakho — indicator madadgaar " +
                    "hai, aaqa nahi. Price hamesha pehle bolti hai, indicator sirf tasdeeq karta hai.",
            points = listOf(
                "EMA 50 aur 200 trend ka filter hain. Price EMA 50 ke upar ho to buyers mazboot " +
                        "hain. Jab EMA 50, EMA 200 ko neeche se upar kaate to golden cross — lambi " +
                        "bullish nishani; upar se neeche kaate to death cross — bearish. Lekin ye " +
                        "lagging hain, matlab der se batate hain — akele in par trade mat karo.",
                "RSI 14 momentum batata hai, 0 se 100 ka scale. 70 se upar overbought — market " +
                        "thak sakta hai; 30 se neeche oversold. Asal khazana divergence hai: price " +
                        "naya high banaye lekin RSI naya high na banaye — matlab taqat khatam ho rahi " +
                        "hai, hoshiyar ho jao.",
                "Volume jhoot nahi bolta. Breakout ya girawat ke saath volume uchhle to move asli " +
                        "hai; khamosh breakout par shak karo. Bina volume ki harkat aksar wapas palat " +
                        "jati hai.",
                "Teeno ko milao: uptrend (EMA) + support ke paas (Lesson 1) + RSI oversold se palat " +
                        "raha ho + volume ke saath — ye confluence hai. Jitni zyada tasdeeq, utna " +
                        "behtar setup."
            ),
            practical = listOf(
                PracticalStep(
                    "TradingView me BTCUSDT ka chart kholo.",
                    "Screen par 'BTCUSDT' ya chart nazar aana chahiye."
                ),
                PracticalStep(
                    "Upar 'Fx' ya 'Indicators' ka button dabao.",
                    "Indicator ki search list khulni chahiye — get_screen_elements me 'Indicators' nazar aaye."
                ),
                PracticalStep(
                    "Search me 'Moving Average Exponential' likh kar add karo.",
                    "Chart par ek EMA line ya indicator list me 'EMA' nazar aana chahiye."
                ),
                PracticalStep(
                    "Uski settings kholo aur length 50 karo. Phir ek aur EMA add kar ke uski length 200 karo.",
                    "Screen par '50' aur '200' ki settings ya do EMA lines nazar aani chahiye."
                ),
                PracticalStep(
                    "Search me 'Relative Strength Index' likh kar add karo.",
                    "Chart ke neeche RSI ka panel nazar aana chahiye."
                ),
                PracticalStep(
                    "Ab chart dekho: price EMA 50 ke upar hai ya neeche? RSI kitna hai? Mujhe batao.",
                    "Us ke jawab par confirm ya correct karo — ye uski observation ki practice hai."
                )
            )
        ),
        ClassLesson(
            title = "Risk management — pro ka asal raaz",
            intro = "Ye sab se ahem lesson hai, Fazil. Pro trader ko pro uski prediction nahi, " +
                    "uski risk management banati hai. Ye lesson kabhi mat bhoolna.",
            points = listOf(
                "1-2% ka usool: ek trade me apne kul paiso ka zyada se zyada 1-2% risk karo. " +
                        "Is ka matlab — 10 trades musalsal haarne ke baad bhi tumhara 80% se zyada " +
                        "paisa bacha rehta hai, aur tum khel me rehte ho.",
                "Stop-loss ki jagah pehle se tay karo: long trade me support se thora neeche, " +
                        "taake normal upar-neeche (noise) tumhein bahar na kare. Stop-loss umeed " +
                        "par nahi, plan par lagta hai — aur lagne ke baad use aage mat sarakao.",
                "Position size ka formula: risk wale paise ÷ (entry − stop-loss). Misal: 1000 USDT " +
                        "account, 1% risk = 10 USDT. Entry 86,000, stop-loss 85,000 — fasla 1,000. " +
                        "Size = 10 ÷ 1000 = 0.01 BTC. Bas, itna hi khareedna hai.",
                "Risk-reward kam az kam 1:2 rakho: 10 ka risk ho to target 20 ka munafa ho. Is " +
                        "ratio se tum aadhi trades haar kar bhi munafa me rehte ho — yehi pro ka hisaab hai."
            ),
            practical = listOf(
                PracticalStep(
                    "Batao: account 2000 USDT hai aur tum 1% risk lete ho — ek trade me kitne USDT risk karo ge?",
                    "Sahi jawab 20 USDT hai. Ghalat ho to narmi se dobara samjhao."
                ),
                PracticalStep(
                    "Entry 87,000 hai, stop-loss 86,000 — 20 USDT risk par position size nikalo.",
                    "Sahi jawab: fasla 1000, size = 20 ÷ 1000 = 0.02 BTC."
                ),
                PracticalStep(
                    "1:2 risk-reward ke liye target price kahan rakho ge?",
                    "Sahi jawab: risk 1000, reward 2000 — target 89,000."
                )
            )
        ),
        ClassLesson(
            title = "Futures — leverage, liquidation, funding",
            intro = "Lesson 4 futures ke baare me hai. Pehle warning sun lo: ye lesson samajhna " +
                    "lazmi hai, lekin futures pe asli paisa lagana aakhri qadam hai — pehle spot, " +
                    "phir practice.",
            points = listOf(
                "Leverage udhaar paisa hai. 10x leverage ka matlab: tumhara position 10 guna bara, " +
                        "munafa 10 guna — AUR nuqsan bhi 10 guna. 10x par sirf 10% ulta move aur " +
                        "tumhara margin khatam.",
                "Liquidation: jab nuqsan tumhara margin kha jaye to exchange tumhari trade " +
                        "zabardasti band kar deti hai — paisa zero. Isolated margin me sirf us trade " +
                        "ka paisa jata hai; cross margin me poore account ka khatra hota hai.",
                "Funding rate: har 8 ghante me longs aur shorts me se ek taraf dusri ko fees deti " +
                        "hai. Bheer jis taraf zyada ho, us taraf khare rehna roz thora mehenga parta " +
                        "hai — lambi trade me iska hisaab rakho.",
                "SAKHT WARNING: leverage se ameer hone wale kam hain, zero hone wale zyada. Pehle " +
                        "spot par mahino seekho, phir testnet par futures ki practice karo, aur sirf " +
                        "tab chhota leverage use karo jab tum musalsal munafa dikha sako. Jaldi " +
                        "leverage = jaldi zero."
            ),
            practical = listOf(
                PracticalStep(
                    "Binance app kholo aur Futures section me jao — trade MAT karo, sirf calculator dekho.",
                    "Screen par 'Futures' ya 'USDⓈ-M' nazar aana chahiye."
                ),
                PracticalStep(
                    "Calculator me 10x leverage, entry 86,000 daal kar liquidation price dekho — wo entry se kitni door hai?",
                    "Us ke jawab par confirm karo — maqsad ye ehsaas hai ke 10x par kitni chhoti move kafi hai."
                ),
                PracticalStep(
                    "Margin mode me Isolated select karo aur dekho — ab samjhao, isolated aur cross me kya farq hai?",
                    "Us se suno aur theek karo: isolated = sirf us trade ka risk, cross = poora account."
                )
            )
        ),
        ClassLesson(
            title = "Trade planning — checklist aur journal",
            intro = "Lesson 5: pro trader trade se pehle plan likhta hai. Bina plan ki trade " +
                    "jua hai, plan wali trade business hai.",
            points = listOf(
                "Entry checklist: teeno cheezein ek taraf hon to entry socho — trend tumhare haq " +
                        "me, price support/resistance ke qareeb, indicator tasdeeq kare. Do hon to " +
                        "intezar karo, ek ho to bhool jao.",
                "Plan pehle, trade baad me: entry, stop-loss aur target trade se PEHLE likho. " +
                        "Trade ke beech me plan badalna sakht mana hai — wahan jazbaat bolte hain, aqal nahi.",
                "Trading journal: har trade me date, pair, dakhil hone ki wajah, entry, stop-loss, " +
                        "target, natija aur EK sabaq likho. Hafte me ek baar journal parho — ye tumhara " +
                        "asal ustad hai, main to sirf shuruwat hoon."
            ),
            practical = listOf(
                PracticalStep(
                    "BTC ka aaj ka chart kholo aur checklist banao: trend kya hai? qareebi support aur resistance kahan? RSI kya keh raha hai? Mujhe teeno batao.",
                    "Us ke jawab ko checklist se milao — jo ghalat ho narmi se theek karo."
                ),
                PracticalStep(
                    "Ek sample plan likho (paper par ya notes me): entry, stop-loss, target aur risk %. Phir mujhe sunao.",
                    "Check karo: kya stop-loss support ke neeche hai? kya risk-reward 1:2 ya behtar hai?"
                )
            )
        ),
        ClassLesson(
            title = "Psychology — dimagh ka khel",
            intro = "Aakhri lesson, aur shayad sab se mushkil: trading me tumhara sab se bara " +
                    "dushman market nahi, tumhare apne jazbaat hain.",
            points = listOf(
                "FOMO: bhaagti train ke peechay mat daudo. Price jab tez bhag rahi ho to dil " +
                        "kahega 'abhi ghuso' — wahi waqt rukne ka hai. Moka roz aata hai, paisa ek " +
                        "baar gaya to wapas nahi aata.",
                "Revenge trading: bare nuqsan ke baad foran badla lene ki koshish — ye nuqsan " +
                        "ko dogna karti hai. Nuqsan ho to screen band karo, chai piyo, kal phir din hai.",
                "Sabar: pro trader din me 10 trades nahi karta. Mahine ki 4-5 achi, plan wali " +
                        "trades kaafi hain. Intezar bhi ek position hai.",
                "Losing streak normal hai — duniya ke best traders bhi musalsal haarte hain. Isi " +
                        "liye 1-2% ka usool hai. Lagatar haar par break lo, journal parho, chhote se " +
                        "dobara shuru karo."
            ),
            practical = listOf(
                PracticalStep(
                    "Apne liye 3 usool likho jo tum kabhi nahi toro ge — misal: '1% se zyada risk nahi'. Mujhe sunao.",
                    "Us ke usoolon ko dohrao aur tareef karo — ye uska zaati trading dastoor hai."
                ),
                PracticalStep(
                    "Ab ek gehra saans lo — tum ne 6 lessons mukammal kar liye. Agla qadam: ek mahina demo ya chhoti amount par sirf plan wali trades. Taiyaar ho?",
                    "Us ke jawab par mubarakbad do aur class mukammal karo."
                )
            )
        )
    )

    /** Prompt block appended to the system instruction in GeminiLiveClient.sendSetup(). */
    fun classModeBlock(): String =
        "TRADING CLASS MODE — Fazil ki Binance trading class. " +
                "Jab user kahe \"trading class shuru karo\" / \"trading seekhao\" / \"class shuru karo\" / " +
                "\"start trading class\" to foran trading_class tool action=start call karo. " +
                "Jab kahe \"class band karo\" / \"class khatam\" / \"stop class\" to action=stop call karo. " +
                "Jab tool kahe class mode ON hai, to tum uski TRADING USTAD ho — bachpan ki dost wali " +
                "garmi ke saath ustad ka sabar: " +
                "Tool jo point ya micro-step bhejta hai, SIRF wahi parhao — apne alfaaz me, Roman Urdu me, " +
                "mukhtasir aur saaf. Ek waqt me EK point, kabhi do points ikathay mat parhao. " +
                "Har point ke baad poocho: \"Fazil, samajh aaya? Koi sawal hai?\" Sawal aaye to poora aur " +
                "aasan jawab do, phir poocho: \"Aur koi sawal, ya agla point chalen?\" — \"agla\" kahe to " +
                "trading_class action=next call karo; sawal na ho aur \"haan\" kahe to bhi action=next. " +
                "Practical me SIRF EK micro-step do, phir ruko. Phir verify karo: get_screen_elements se " +
                "screen parho — jahan mumkin ho khud confirm karo (\"shabash Fazil, lag gaya!\") ya narmi se " +
                "theek karo (\"lagta hai wo menu nahi khula — dobara try karo\"). Jahan screen se verify na " +
                "ho sake (jaise line ki sahi jagah), us se poocho \"ho gaya?\" aur khud-check ka usool sikhayo. " +
                "Lehja: sabar wali dost — halki hosla-afzai (\"bohat aala!\", \"shabash!\"), kabhi lecture mat " +
                "jharo, kabhi ghussa mat karo. Feminine grammar, uska naam lo. " +
                "SAKHT HADDEIN (kabhi mat todo): kabhi up/down signal mat do — agle minute/hour me price " +
                "kahan jayegi ye kehna SAKHT MANA hai; munafa ka wada kabhi nahi; risk management hamesha " +
                "pehle; futures ke practical se PEHLE leverage/liquidation warning LAZMI hai. " +
                "Lessons: 1 Support/Resistance/Trend, 2 Indicators (EMA 50/200, RSI 14, Volume), " +
                "3 Risk Management (1-2% rule, stop-loss, position size, 1:2 R:R), 4 Futures (leverage, " +
                "liquidation, funding — sakht warning), 5 Trade Planning (checklist, journal), " +
                "6 Psychology (FOMO, revenge trading, sabar)."

    /**
     * Handles the trading_class tool. Returns the complete "OK: ..."/"ERROR: ..."
     * result string, including the exact chunk to teach right now.
     */
    fun handleAction(action: String, lessonArg: Int, pointArg: Int, context: Context): String {
        return try {
            TradingClassState.init(context)
            val st = TradingClassState
            when (action.lowercase()) {
                "start" -> startClass(st, lessonArg)
                "stop" -> {
                    st.active = false
                    st.save()
                    "OK: class mode band kar diya. Jab dil kare dobara kehna \"trading class shuru karo\" — " +
                            "tumhari progress (lesson ${st.lesson}) mehfooz hai, wahin se shuru karenge."
                }
                "goto" -> {
                    val l = lessonArg.coerceIn(1, lessons.size)
                    st.active = true
                    st.lesson = l
                    st.point = pointArg.coerceIn(0, lessons[l - 1].points.size)
                    st.pstep = 0
                    st.save()
                    currentChunk(st)
                }
                "next" -> {
                    if (!st.active) {
                        st.active = true
                    }
                    advance(st)
                    if (st.lesson > lessons.size) {
                        // All 6 lessons done — celebrate and close the class.
                        st.lesson = lessons.size
                        st.point = 0
                        st.pstep = 0
                        st.active = false
                        st.save()
                        "OK: class mode band — SAARE 6 LESSONS MUKAMMAL! Fazil ko mubarakbad do: \"Fazil, " +
                                "mubarak ho! Tum ne poori trading class mukammal kar li — support/resistance, " +
                                "indicators, risk management, futures, planning aur psychology! Ab ek mahina demo " +
                                "ya chhoti amount par sirf plan wali trades karo. Jab chaaho 'trading class shuru " +
                                "karo' keh kar koi bhi lesson dobara parh sakte ho.\""
                    } else currentChunk(st)
                }
                "repeat" -> currentChunk(st)
                else -> "ERROR: action samajh nahi aaya — 'start', 'next', 'repeat', 'goto' ya 'stop' me se ek do."
            }
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    private fun startClass(st: TradingClassState, lessonArg: Int): String {
        st.active = true
        if (lessonArg in 1..lessons.size) {
            st.lesson = lessonArg
            st.point = 0
            st.pstep = 0
            st.save()
            return currentChunk(st)
        }
        val saved = st.lesson
        if (saved in 1..lessons.size) {
            st.save()
            val title = lessons[saved - 1].title
            return "OK: class mode ON hai. SAVED PROGRESS: Fazil pichli baar Lesson $saved ($title) par tha. " +
                    "Pehle us se poocho: \"Fazil, pichli baar tum lesson $saved par thay — wahin se shuru karun " +
                    "ya dobara pehle lesson se?\" Uske jawab ke mutabiq: \"wahin se\" kahe to trading_class " +
                    "action=goto lesson=$saved call karo; \"dobara\" kahe to action=goto lesson=1 call karo. " +
                    "Abhi kuch mat parhao — pehle uska jawab lo."
        }
        // Fresh start.
        st.lesson = 1
        st.point = 0
        st.pstep = 0
        st.save()
        try {
            MyraMemory.addFact("Fazil MYRA se Binance spot/futures trading class le raha hai")
        } catch (_: Exception) {
        }
        return "OK: class mode ON hai — ye pehli class hai. Pehle khush-ikhlaaqi se class ka isteqbal karo: " +
                "\"Fazil, trading class me khush aamdeed! Main tumhein point by point parhaungi — jahan " +
                "sawal ho beech me rok kar pooch lena.\" Phir neeche wala PEHLA POINT parhao.\n" +
                currentChunk(st)
    }

    /** Move to the next point / practical step / lesson. */
    private fun advance(st: TradingClassState) {
        val l = lessons[(st.lesson.coerceIn(1, lessons.size)) - 1]
        if (st.point < l.points.size) {
            st.point++
        } else if (st.pstep < l.practical.size - 1) {
            st.pstep++
        } else {
            st.lesson++
            st.point = 0
            st.pstep = 0
        }
        st.save()
    }

    /** The exact chunk the model must teach right now. */
    private fun currentChunk(st: TradingClassState): String {
        if (st.lesson !in 1..lessons.size) {
            st.lesson = 1
            st.point = 0
            st.pstep = 0
        }
        val idx = st.lesson - 1
        val l = lessons[idx]
        val sb = StringBuilder()
        if (st.point < l.points.size) {
            val total = l.points.size
            sb.append("OK: class mode ON — Lesson ${st.lesson}: ${l.title} (point ${st.point + 1}/$total).\n")
            if (st.point == 0) {
                sb.append("Lesson ka intro (pehle ye ek line kaho): \"${l.intro}\"\n")
            }
            sb.append("ABHI YE POINT PARHAO (apne alfaaz me, Roman Urdu me):\n${l.points[st.point]}\n")
            sb.append("Parhane ke baad LAZMI poocho: \"Fazil, samajh aaya? Koi sawal hai?\" ")
            sb.append("Sawal aaye to poora aasan jawab do, phir poocho \"aur koi sawal, ya agla point chalen?\" — ")
            sb.append("\"agla\" ya \"haan\" kahe to trading_class action=next call karo.")
        } else {
            val total = l.practical.size
            val step = l.practical[st.pstep.coerceIn(0, total - 1)]
            sb.append("OK: class mode ON — Lesson ${st.lesson} PRACTICAL (step ${st.pstep + 1}/$total). Fazil khud TradingView chalayega, tum guide karo.\n")
            sb.append("SIRF YE EK MICRO-STEP DO (is se zyada kuch mat batao):\n${step.text}\n")
            sb.append("Phir VERIFY karo — hint: ${step.verifyHint} ")
            sb.append("Verify ho jaye to kaho \"shabash Fazil!\" aur agle step ke liye trading_class action=next call karo. ")
            sb.append("Na ho to narmi se dobara samjhao aur phir verify karo.")
            if (st.pstep == total - 1) {
                sb.append(" Ye aakhri step hai — iske baad action=next se agla lesson shuru hoga.")
            }
        }
        return sb.toString()
    }
}
