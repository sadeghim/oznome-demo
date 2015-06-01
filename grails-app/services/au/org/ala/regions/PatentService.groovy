package au.org.ala.regions

import com.gmongo.GMongo
import groovy.time.TimeCategory
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

import java.util.concurrent.ConcurrentHashMap

import static groovyx.gpars.GParsPool.withPool

class PatentService {

    static final String AUSPAT_URL = 'http://pericles.ipaustralia.gov.au/ols/auspat/advancedSearch.do?resultsPerPage=1000&includeTextSearch=on&submit=Search&queryString='

    static final String SEARCH_RESULTS_TABLE = "#rawresults tbody tr.metadata"

    static final int THREADS = 5
    static final int MAX_CACHE_AGE_MINUTES = 60

    def fetchAll(json) {
        Map<String, String> results = [:] as ConcurrentHashMap

        withPool(THREADS) {
            json.eachParallel {
                def result = getCached(it)
                if (result == null) {
                    result = fetch(it)
                } else {
                    log.debug("${it} was cached")
                }
                results << [(it): result]
            }
        }

        results
    }

    def fetch(String speciesName) {
        log.debug("Fetching ${speciesName}...")

        // search claims, title and abstract
        String query = "(\"${speciesName}\" IN CS) OR (\"${speciesName}\" IN TI) OR (\"${speciesName}\" IN AB)"
        query = URLEncoder.encode(query, "UTF-8")

        Document doc = Jsoup.connect("${AUSPAT_URL}${query}").timeout(30000).cookie("hasAccepted", "true").get()

        def searchResults = doc.select(SEARCH_RESULTS_TABLE)

        List result = searchResults.collect {
            def columns = it.select("td")
            [
                    applicationNumber: columns[1].select("a").text(),
                    title            : columns[2].text(),
                    applicants       : columns[3].text(),
                    inventors        : columns[4].text(),
                    filingDate       : columns[5].text(),
                    filingStatus     : columns[6].text()
            ]
        }

        def db = new GMongo().getDB("patents")
        db.patents.insert([species: speciesName, results: result, cacheTimestamp: new Date()])

        result
    }

    def getCached(String speciesName) {
        def db = new GMongo().getDB("patents")
        def cached = db.patents.findOne(species: speciesName)

        if (cached) {
            def timeInCache = TimeCategory.minus(new Date(), cached.cacheTimestamp as Date)
            if (timeInCache.minutes > MAX_CACHE_AGE_MINUTES) {
                log.debug("${speciesName} was cached ${timeInCache.minutes} minutes ago - too old, removing from cache")
                db.patents.remove(species: speciesName)
                cached = null
            }
        }

        cached?.results
    }
}