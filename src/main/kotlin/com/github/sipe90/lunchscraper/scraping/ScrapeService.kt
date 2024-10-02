package com.github.sipe90.lunchscraper.scraping

import com.github.sipe90.lunchscraper.config.LocationConfig
import com.github.sipe90.lunchscraper.config.LunchScraperConfiguration
import com.github.sipe90.lunchscraper.config.RestaurantConfig
import com.github.sipe90.lunchscraper.domain.MenuScrapeResult
import com.github.sipe90.lunchscraper.html.DocumentCleaner
import com.github.sipe90.lunchscraper.html.DocumentLoader
import com.github.sipe90.lunchscraper.service.MenuService
import com.github.sipe90.lunchscraper.util.Utils
import com.github.sipe90.lunchscraper.util.md5
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.Clock
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

@Service
class ScrapeService(
    config: LunchScraperConfiguration,
    private val extractionService: ExtractionService,
    private val menuService: MenuService,
) {
    private val saveDocument = config.scrapingConfig.saveDocument

    private val locations = config.locations

    suspend fun scrapeAllMenus() {
        locations.values.map { scrapeAllLocationMenus(it).awaitAll() }
    }

    suspend fun scrapeAllLocationMenus(locationId: String) {
        val location = locations[locationId] ?: return
        scrapeAllLocationMenus(location).awaitAll()
    }

    suspend fun scrapeRestaurantMenus(
        locationId: String,
        restaurantId: String,
    ) {
        val location = locations[locationId] ?: return
        val restaurant = location.restaurants[restaurantId] ?: return

        scrapeRestaurantMenus(location, restaurant)
    }

    private suspend fun scrapeAllLocationMenus(location: LocationConfig) =
        coroutineScope {
            location.restaurants.values.map {
                async { scrapeRestaurantMenus(location, it) }
            }
        }

    private suspend fun scrapeRestaurantMenus(
        location: LocationConfig,
        restaurant: RestaurantConfig,
    ) = coroutineScope {
        logger.info { "Scraping menus for restaurant ${restaurant.id}" }

        val existingScrapeResult = menuService.getMenus(location.id, restaurant.id)

        val htmlDocs =
            restaurant.urls
                .map {
                    async {
                        DocumentLoader.loadHtmlDocument(it).let {
                            DocumentCleaner.cleanDocument(it)
                        }
                    }
                }.awaitAll()

        val cleanedDocs = htmlDocs.joinToString("\n")
        val documentHash = cleanedDocs.md5()

        if (existingScrapeResult != null) {
            if (existingScrapeResult.documentHash == documentHash) {
                logger.info { "Skipping extraction for ${restaurant.id} since document hash matches with previous scrape result hash" }
                return@coroutineScope
            }
            logger.info { "Document hash changed from previous scrape for ${restaurant.id}. Proceeding with scrape." }
        } else {
            logger.info { "No previous scrape result found for ${restaurant.id}. Proceeding with scrape." }
        }

        val extractionResult = extractionService.extractMenusFromDocument(cleanedDocs, restaurant.hint)

        logger.info { "Finished scraping menus for restaurant ${restaurant.id}" }

        val scrapeResult =
            MenuScrapeResult(
                year = Utils.getCurrentYear(),
                week = Utils.getCurrentWeek(),
                locationId = location.id,
                restaurantId = restaurant.id,
                document = if (saveDocument) cleanedDocs else null,
                documentHash = documentHash,
                scrapeTimestamp = Clock.System.now(),
                extractionResult = extractionResult,
            )

        menuService.saveMenus(scrapeResult)
    }
}
