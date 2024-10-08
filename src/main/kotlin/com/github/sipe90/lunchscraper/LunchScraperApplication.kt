package com.github.sipe90.lunchscraper

import com.github.sipe90.lunchscraper.config.LunchScraperConfiguration
import com.github.sipe90.lunchscraper.plugins.configureRouting
import com.github.sipe90.lunchscraper.plugins.configureSecurity
import com.github.sipe90.lunchscraper.plugins.configureSerialization
import com.github.sipe90.lunchscraper.plugins.configureSpringDI
import com.github.sipe90.lunchscraper.tasks.ScrapeScheduler
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.util.logging.Level
import java.util.logging.Logger

fun main(args: Array<String>) {
    Logger.getLogger("").level = Level.OFF
    io.ktor.server.netty.EngineMain
        .main(args)
}

fun Application.module() {
    val config = environment.config.config("lunch-scraper")
    val ctx =
        AnnotationConfigApplicationContext().also {
            it.registerBean(LunchScraperConfiguration::class.java, config)
            it.scan("com.github.sipe90.lunchscraper")
            it.refresh()
        }

    val configBean = ctx.getBean(LunchScraperConfiguration::class.java)
    val scrapeSchedulerBean = ctx.getBean(ScrapeScheduler::class.java)

    configureSecurity(configBean.apiConfig.apiKey)
    configureRouting()
    configureSerialization()
    configureSpringDI(ctx)

    monitor.subscribe(ApplicationStarted) {
        if (configBean.schedulerConfig.enabled) {
            scrapeSchedulerBean.start()
        }
    }

    monitor.subscribe(ApplicationStopped) {
        if (scrapeSchedulerBean.isRunning()) {
            scrapeSchedulerBean.shutdown()
        }
    }
}
