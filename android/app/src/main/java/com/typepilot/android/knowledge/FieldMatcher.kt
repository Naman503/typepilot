package com.typepilot.android.knowledge

import kotlin.math.max
import kotlin.math.min

/**
 * The instant answer engine: given the question a field is asking ("Mobile number*", "What is your current CTC?"),
 * score every known fact and return the likeliest answers with a percentage. No AI, no network: under a millisecond.
 *
 * Score = how well the question matches the fact's key/aliases (concept synonyms, word overlap, letter trigrams for
 * typos) × how trusted the fact is (typed often, accepted, from your documents).
 */
object FieldMatcher {
    /** percent = match × trust (both 0–100). */
    data class Match(val fact: Fact, val percent: Int, val reason: String, val match: Int = percent, val trust: Int = 100)

    /** Common form concepts and the many ways forms ask for them. */
    val concepts: Map<String, List<String>> = mapOf(
        "full_name" to listOf("full name", "name", "your name", "candidate name", "applicant name", "legal name", "name as per"),
        "first_name" to listOf("first name", "given name", "fname", "forename"),
        "last_name" to listOf("last name", "surname", "family name", "lname"),
        "email" to listOf("email", "e mail", "email address", "email id", "mail id", "mail", "work email", "personal email"),
        "phone" to listOf("phone", "mobile", "mobile number", "phone number", "contact number", "contact no", "mobile no", "whatsapp", "cell", "telephone"),
        "address" to listOf("address", "street address", "residential address", "current address", "permanent address", "address line"),
        "city" to listOf("city", "town", "current city", "location", "current location", "preferred location"),
        "state" to listOf("state", "province", "region"),
        "pincode" to listOf("pincode", "pin code", "zip", "zip code", "postal code", "postcode"),
        "country" to listOf("country", "nationality country"),
        "dob" to listOf("date of birth", "dob", "birth date", "birthday"),
        "gender" to listOf("gender", "sex"),
        "company" to listOf("company", "current company", "employer", "current employer", "organization", "organisation", "company name"),
        "title" to listOf("designation", "job title", "title", "current designation", "role", "current role", "position"),
        "experience" to listOf("experience", "total experience", "years of experience", "work experience", "yoe", "relevant experience"),
        "current_ctc" to listOf("current ctc", "current salary", "ctc", "present salary", "current compensation", "current package"),
        "expected_ctc" to listOf("expected ctc", "expected salary", "salary expectation", "expected compensation", "expected package"),
        "notice" to listOf("notice period", "notice", "joining time", "how soon can you join", "earliest joining date", "availability to join"),
        "linkedin" to listOf("linkedin", "linkedin profile", "linkedin url"),
        "github" to listOf("github", "github profile", "github url"),
        "website" to listOf("website", "portfolio", "personal website", "portfolio url", "blog"),
        "skills" to listOf("skills", "key skills", "technical skills", "skill set", "tech stack"),
        "degree" to listOf("degree", "qualification", "highest qualification", "education", "highest education"),
        "college" to listOf("college", "university", "institute", "school", "alma mater"),
        "grad_year" to listOf("graduation year", "year of passing", "passing year", "batch"),
        "languages" to listOf("languages", "languages known", "spoken languages"),
        "summary" to listOf("about yourself", "about you", "summary", "profile summary", "bio", "introduce yourself", "tell us about yourself"),
    )

    private val stop = setOf("your", "the", "please", "enter", "what", "is", "of", "a", "an", "in", "to", "do", "you", "are", "my", "here", "type",
        "required", "optional", "provide", "field", "and", "or", "for", "with", "us", "we", "this", "that", "i", "me", "be", "will", "can", "how", "much")

    fun normalize(s: String): String = s.lowercase().replace(Regex("""[^\p{L}\p{N} ]+"""), " ").replace(Regex("""\s+"""), " ").trim()

    private fun tokens(s: String): Set<String> = normalize(s).split(' ').filter { it.length > 1 && it !in stop }.toSet()

    private fun trigrams(s: String): Set<String> {
        val t = " ${normalize(s)} "
        return if (t.length < 3) emptySet() else (0..t.length - 3).map { t.substring(it, it + 3) }.toSet()
    }

    /** The concept a question or key is about ("Mobile no.*" → phone), longest synonym wins. */
    fun concept(text: String): String? {
        val n = " ${normalize(text)} "
        var best: Pair<String, Int>? = null
        for ((c, syns) in concepts) for (syn in syns) {
            if (n.contains(" $syn ") && (best == null || syn.length > best.second)) best = c to syn.length
        }
        return best?.first
    }

    private fun jaccard(a: Set<String>, b: Set<String>) = if (a.isEmpty() || b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / a.union(b).size

    /** How well [question] matches a key (0–1). */
    fun similarity(question: String, key: String): Pair<Double, String> {
        val qc = concept(question)
        val kc = concept(key)
        if (qc != null && qc == kc) return 0.97 to "same field type"
        // A full name can answer "name"; first/last names can be split out of it by the caller.
        val tok = jaccard(tokens(question), tokens(key))
        val tri = jaccard(trigrams(question), trigrams(key))
        val score = max(tok, tri * 0.9)
        // Different known concepts (email vs phone) must not match.
        if (qc != null && kc != null && qc != kc) return score * 0.3 to "different field"
        return score to if (tok >= tri) "words match" else "similar wording"
    }

    /**
     * Best answers for a field. [typed] is what's already in the field: answers must start with it.
     * Returns at most [limit] matches above [minPercent], best first.
     */
    /** How much a fact is trusted (0–1.08): its confidence, how often it was used, minus times you closed it. */
    fun trust(f: Fact) = (0.55 + 0.45 * f.confidence + min(0.08, f.uses * 0.02) - min(0.3, f.rejects * 0.1)).coerceAtLeast(0.0)

    /**
     * [app]: the app you're typing in. Answers typed in the same app get a boost; typed answers from other apps are
     * trusted a little less (or skipped entirely when [sameAppOnly]). Document and manual facts work everywhere.
     */
    fun match(question: String, facts: List<Fact>, typed: String = "", limit: Int = 3, minPercent: Int = 55, minUses: Int = 1,
              app: String = "", preferSameApp: Boolean = true, sameAppOnly: Boolean = false): List<Match> {
        if (question.isBlank() && typed.isBlank()) return emptyList()
        val t = typed.trim().lowercase()
        val out = ArrayList<Match>()
        for (f in facts) {
            // Typed answers become suggestions only after you've typed them [minUses] times.
            if (f.source == "typed" && f.uses + 1 < minUses) continue
            val otherApp = app.isNotBlank() && f.source == "typed" && f.apps.isNotEmpty() && app !in f.apps
            if (sameAppOnly && otherApp) continue
            if (t.isNotEmpty() && !(f.value.lowercase().startsWith(t) && f.value.length > t.length)) continue
            var best = 0.0
            var why = ""
            for (k in listOf(f.key) + f.aliases) {
                val (sim, reason) = similarity(question, k)
                if (sim > best) { best = sim; why = reason }
            }
            // Typed a prefix of a known value: likely even if the question is vague.
            if (t.length >= 3 && best < 0.6) { best = max(best, 0.6); why = "you started typing it" }
            if (best <= 0) continue
            var trust = trust(f)
            if (preferSameApp && app.isNotBlank()) {
                if (app in f.apps) { trust += 0.08; why += " · used in this app" } else if (otherApp) trust *= 0.85
            }
            val pct = (best * trust * 100).toInt().coerceIn(0, 99)
            if (pct >= minPercent) out += Match(f, pct, why, (best * 100).toInt(), (trust * 100).toInt())
        }
        return out.sortedByDescending { it.percent }.distinctBy { it.fact.value.lowercase() }.take(limit)
    }

    /** Derive first/last name from a full name when a form asks for them separately. */
    fun derived(question: String, facts: List<Fact>): Match? {
        val c = concept(question) ?: return null
        if (c != "first_name" && c != "last_name") return null
        val full = facts.filter { concept(it.key) == "full_name" || it.aliases.any { a -> concept(a) == "full_name" } }.maxByOrNull { it.confidence } ?: return null
        val parts = full.value.trim().split(Regex("""\s+"""))
        if (parts.size < 2) return null
        val v = if (c == "first_name") parts.first() else parts.last()
        val tr = trust(full)
        return Match(full.copy(value = v, key = if (c == "first_name") "First name" else "Last name"), (90 * tr).toInt().coerceAtMost(99), "from your full name", 90, (tr * 100).toInt())
    }
}
