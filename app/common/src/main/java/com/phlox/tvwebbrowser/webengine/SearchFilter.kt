package com.phlox.tvwebbrowser.webengine

import java.text.Normalizer

/**
 * Blocks searches for porn, alcohol, drugs and gambling, in English, Arabic, Persian, Kurdish, Urdu, Turkish, French,
 * German, Dutch, Spanish and Indonesian/Malay.
 * Learning about it is allowed, getting it isn't:
 *  - "why is alcohol haram", "porn addiction help", "dangers of gambling": allowed (a question word or a religious,
 *    health or quitting word);
 *  - "buy alcohol", "beer", "casino near me", "why buy alcohol": blocked (no such word, or a buying or media word,
 *    which always blocks).
 *
 * Terms: a word, a word with * (any word starting with it) or a phrase of whole words.
 */
object SearchFilter {
    enum class Kind { ADULT, HARAM }

    //"the" with "and", "with", "so", "like", "for" in front: taken off the words (declared before the terms use it)
    private val ARABIC_PREFIXES = listOf("وال", "بال", "فال", "كال", "لل", "ال")

    private val ADULT = Terms(
        //en
        "porn*, xxx, nude*, naked, nudity, nsfw, hentai, onlyfans, sex, sexy, sexting, boobs, tits, pussy, milf, " +
        "fuck*, blowjob*, erotic*, playboy, fetish*, bdsm, striptease, camgirl*, " +
        "xnxx, xvideos, xhamster, pornhub, redtube, youporn, brazzers, chaturbate, " +
        //ar, fa, ku, ur
        "سكس, سكسي, اباحي, اباحيه, اباحية, عاري, عاريه, عاريات, عراة, " +
        "سکس, سکسی, پورن, پورنو, برهنه, لخت, " +
        "سێکس, سێکسی, پۆرن, ڕووت, " +
        "فحش, فحاشی, ننگی, " +
        //tr, fr, de, nl, es, id
        "çıplak, sikiş, " + "nue, nues, sexe, " + "nackt, nacktbilder, " + "naakt, seks, " +
        "desnuda, desnudas, desnudo, sexo, " + "bokep, telanjang, bugil, ngentot, mesum"
    )

    private val HARAM = Terms(
        //en: alcohol
        "alcohol, alcoholic, booze, beer*, wine, wines, winery, vodka, whisky, whiskey, liquor*, rum, tequila, brandy, " +
        "champagne, cognac, bourbon, brewery, breweries, mojito, prosecco, " +
        //en: gambling
        "casino*, gambling, gamble, betting, bet365, sportsbook, bookmaker*, poker, blackjack, roulette, slot machine, " +
        "slot machines, lottery, lotto, jackpot, " +
        //en: drugs, vaping
        "cannabis, marijuana, smoke weed, buy weed, weed strain, weed strains, cocaine, heroin, meth, " +
        "methamphetamine, mdma, lsd, ecstasy pills, magic mushrooms, ketamine, vape*, vaping, cbd, thc, kratom, " +
        //ar
        "خمر, خمور, كحول, كحوليه, كحولية, بيره, بيرة, نبيذ, فودكا, ويسكي, شمبانيا, " +
        "قمار, كازينو, مراهنات, مراهنه, رهان, يانصيب, بوكر, روليت, " +
        "مخدرات, حشيش, ماريجوانا, كوكايين, هيروين, " +
        //fa
        "الکل, الکلی, آبجو, ودکا, ویسکی, شراب, قمار, کازینو, شرط بندی, شرطبندی, مواد مخدر, ماری جوانا, " +
        //ku: Sorani, Kurmanji
        "ئەلکهول, ئەلکول, بیرە, شەراب, قومار, کازینۆ, ماددەی هۆشبەر, " + "alkol, şerab, qumar, " +
        //ur
        "شراب, منشیات, " +
        //tr
        "bira, şarap, rakı, votka, viski, kumar, kumarhane, bahis, iddaa, uyuşturucu, esrar, " +
        //fr
        "alcool, bière, bières, vin, vins, jeux d'argent, paris sportifs, drogue, drogues, " +
        //de
        "alkohol, bier, wein, schnaps, glücksspiel, wetten, sportwetten, spielothek, drogen, " +
        //nl
        "bier, wijn, gokken, drugs, " +
        //es
        "cerveza, cervezas, vino, vinos, apuestas, drogas, " +
        //id, ms
        "alkohol, arak, judi, taruhan, togel, narkoba, dadah, ganja"
    )

    //asking about it
    private val LEARNING = Terms(
        "why, haram, halal, islam*, muslim*, quran, koran, hadith, fatwa, ruling, sin, sinful, forbidden, prohibited, " +
        "prohibition, harm, harms, harmful, danger*, effect*, health, addict*, quit*, stop, stopping, recover*, rehab, " +
        "help, overcome, avoid, treatment, withdrawal, history, meaning, definition, statistics, law, laws, ban, " +
        "banned, illegal, education, biology, gender, baby, pregnancy, chromosome*, research, study, " +
        //ar, fa, ku, ur
        "لماذا, ليش, ليه, حرام, حلال, حكم, فتوى, فتوي, اسلام, قران, حديث, ضرر, اضرار, مخاطر, خطر, ادمان, اقلاع, توبه, " +
        "علاج, تحريم, محرم, عقوبه, " +
        "چرا, حکم, گناه, مضرات, اعتیاد, ترک, درمان, " +
        "بۆچی, حەرام, حەڵاڵ, ئیسلام, زیان, " + "çima, heram, helal, îslam, zirar, " +
        "کیوں, نقصانات, " +
        //tr, fr, de, nl, es, id
        "neden, niçin, niye, günah, zarar, zararları, bağımlılık, bırakmak, tedavi, yasak, hükmü, fetva, " +
        "pourquoi, péché, interdit, dangers, effets, santé, dépendance, arrêter, sevrage, aide, " +
        "warum, sünde, verboten, gefahr*, schädlich, wirkung*, gesundheit, sucht, aufhören, entzug, hilfe, " +
        "waarom, zonde, verboden, gevaar*, schadelijk, gezondheid, verslaving, stoppen, hulp, " +
        "por qué, porque, pecado, prohibido, peligro*, efectos, salud, adicción, ayuda, " +
        "kenapa, mengapa, hukum, dosa, bahaya, dampak, kesehatan, kecanduan, berhenti, larangan"
    )

    //getting it: blocks even with a learning word
    private val GETTING = Terms(
        "buy, buying, order, shop, shops, shopping, store, price, prices, cheap, sale, deal, deals, delivery, deliver, " +
        "near me, nearby, sell, seller, coupon, discount, promo, deposit, bonus, odds, sign up, " +
        "pics, pic, photos, images, video, videos, clip, clips, gif, gifs, hd, download, stream, tube, cam, cams, " +
        "webcam, gallery, " +
        //ar, fa, ku, ur
        "شراء, اشتري, اشترى, اطلب, طلب, سعر, اسعار, توصيل, متجر, بيع, قريب مني, صور, فيديو, فيديوهات, مقاطع, " +
        "افلام, تحميل, بث, " +
        "خرید, قیمت, سفارش, فروش, عکس, تصاویر, فیلم, ویدیو, دانلود, " +
        "کڕین, نرخ, فرۆشتن, وێنە, ڤیدیۆ, فیلم, " + "kirîn, biha, wêne, vîdyo, " +
        "خریدیں, قیمت, تصاویر, ویڈیو, " +
        //tr, fr, de, nl, es, id
        "satın, satin al, fiyat, fiyatı, sipariş, mağaza, ucuz, resim, resimleri, foto, video, indir, izle, " +
        "acheter, achat, prix, commander, livraison, magasin, pas cher, vidéo, vidéos, télécharger, " +
        "kaufen, preis, preise, bestellen, lieferung, günstig, bilder, fotos, " +
        "kopen, prijs, bezorgen, winkel, goedkoop, " +
        "comprar, precio, precios, pedir, tienda, barato, envío, descargar, " +
        "beli, harga, pesan, toko, murah, jual, unduh, nonton"
    )

    /** What the search is for (when [enabled]). Null: allowed. */
    fun kindOf(query: String, enabled: Set<Kind>): Kind? {
        if (enabled.isEmpty() || query.isBlank()) return null
        val words = words(query)
        if (words.isEmpty()) return null
        val kind = when {
            Kind.ADULT in enabled && ADULT.matches(words) -> Kind.ADULT
            Kind.HARAM in enabled && HARAM.matches(words) -> Kind.HARAM
            else -> return null
        }
        if (GETTING.matches(words)) return kind
        return if (LEARNING.matches(words)) null else kind
    }

    private class Terms(list: String) {
        private val words = HashSet<String>()
        private val prefixes = ArrayList<String>()
        private val phrases = ArrayList<List<String>>()

        init {
            for (term in list.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                val prefix = term.endsWith('*')
                val parts = words(term.trimEnd('*'))
                when {
                    parts.size > 1 -> phrases.add(parts)
                    parts.isEmpty() -> Unit
                    prefix -> prefixes.add(parts[0])
                    else -> words.add(parts[0])
                }
            }
        }

        fun matches(query: List<String>): Boolean =
            query.any { word -> word in words || prefixes.any { word.startsWith(it) } } ||
                phrases.any { phrase -> query.windowed(phrase.size).any { it == phrase } }
    }

    /** Lowercase words without accents, Arabic letters in one form and without the "al" in front */
    private fun words(text: String): List<String> {
        val plain = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("[\\p{Mn}\\u0640]"), "") //accents, Arabic vowel marks, tatweel
            .replace(Regex("[أإآٱ]"), "ا").replace('ى', 'ي').replace('ی', 'ي').replace('ک', 'ك').replace('ة', 'ه')
            .replace(Regex("['’]"), " ")
        return plain.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.map { word ->
            ARABIC_PREFIXES.firstOrNull { word.startsWith(it) && word.length >= it.length + 3 }
                ?.let { word.substring(it.length) } ?: word
        }
    }
}
