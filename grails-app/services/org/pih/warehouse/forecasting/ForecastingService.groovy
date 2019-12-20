/**
 * Copyright (c) 2012 Partners In Health.  All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file epl-v10.html at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 * You must not remove this notice, or any other, from this software.
 **/
package org.pih.warehouse.forecasting

import groovy.sql.Sql
import org.pih.warehouse.core.Location
import org.pih.warehouse.product.Product

import java.sql.Timestamp
import java.text.DateFormatSymbols
import java.text.NumberFormat

class ForecastingService {

    boolean transactional = false

    def dataSource
    def grailsApplication
    def inventoryService

    def getDemand(Location origin, Product product) {

        boolean forecastingEnabled = grailsApplication.config.openboxes.forecasting.enabled ?: false
        if (forecastingEnabled) {
            def numberFormat = NumberFormat.getIntegerInstance()
            def rows = getDemandDetails(origin, product)
            def startDate = rows.min { it.date_requested }?.date_requested
            def endDate = new Date()
            def totalDemand = rows.sum { it.quantity_demand } ?: 0
            def totalDays = (startDate && endDate) ? (endDate - startDate) : 1
            def dailyDemand = (totalDemand && totalDays) ? (totalDemand / totalDays) : 0
            def monthlyDemand = dailyDemand * 30
            def quantityOnHand = inventoryService.getQuantityOnHand(origin, product)
            def onHandMonths = monthlyDemand ? quantityOnHand / monthlyDemand : 0

            return [
                    dateRange    : [startDate: startDate, endDate: endDate],
                    totalDemand  : totalDemand,
                    totalDays    : totalDays,
                    dailyDemand  : dailyDemand,
                    monthlyDemand: "${numberFormat.format(monthlyDemand)}",
                    onHandMonths: onHandMonths
            ]
        }
    }

    def getDemandDetails(Location origin, Product product) {
        List data = []
        Integer demandPeriod = grailsApplication.config.openboxes.forecasting.demandPeriod?:180
        boolean forecastingEnabled = grailsApplication.config.openboxes.forecasting.enabled ?: false
        if (forecastingEnabled) {
            String query = """
                select 
                    request_status,
                    request_number,
                    DATE_FORMAT(date_requested, '%b %Y') as month_requested,
                    date_requested,
                    origin_name,
                    destination_name,
                    product_code,
                    product_name,
                    quantity_requested,
                    quantity_canceled,
                    quantity_approved,
                    quantity_modified,
                    quantity_substituted,
                    quantity_picked,
                    quantity_demand,
                    reason_code_classification
                FROM product_demand_details
                WHERE product_id = :productId
                AND origin_id = :originId
                AND date_requested BETWEEN DATE_SUB(now(), INTERVAL :demandPeriod DAY) AND now()
            """
            Sql sql = new Sql(dataSource)
            try {
                data = sql.rows(query, [productId: product.id, originId: origin.id, demandPeriod: demandPeriod])

            } catch (Exception e) {
                log.error("Unable to execute query: " + e.message, e)
            }
        }
        return data
    }


    def getDemandSummary(Location origin, Product product) {
        List data = []
        Integer demandPeriod = grailsApplication.config.openboxes.forecasting.demandPeriod?:180
        boolean forecastingEnabled = grailsApplication.config.openboxes.forecasting.enabled ?: false
        if (forecastingEnabled) {
            String query = """
                select 
                    min(date_requested) as min_date_requested,
                    max(date_requested) as max_date_requested,
                    month(date_requested) as request_month,
                    year(date_requested) as request_year,
                    sum(quantity_requested) as quantity_requested,
                    sum(quantity_canceled) as quantity_canceled,
                    sum(quantity_approved) as quantity_approved,
                    sum(quantity_modified) as quantity_modified,
                    sum(quantity_substituted) as quantity_substituted,
                    sum(quantity_demand) as quantity_demand
                FROM product_demand_details
                WHERE product_id = :productId
                AND origin_id = :originId
                AND date_requested BETWEEN DATE_SUB(now(), INTERVAL :demandPeriod DAY) AND now()
                GROUP BY request_month, request_year
                ORDER BY request_year, request_month
            """
            Sql sql = new Sql(dataSource)
            List rows = []

            try {
                rows = sql.rows(query, [productId: product.id, originId: origin.id, demandPeriod: demandPeriod])
            } catch (Exception e) {
                log.error("Unable to execute query: " + e.message, e)
            }

            if (rows) {
                Timestamp startDate = rows.min { it.min_date_requested }?.min_date_requested
                Timestamp endDate = rows.max { it.max_date_requested }?.max_date_requested
                List allMonths = getMonths(startDate, endDate)
                def numberFormat = NumberFormat.getIntegerInstance()
                def totalDemand = 0
                def numberOfDays = new Date() - startDate

                data = allMonths.collect { monthYear ->
                    // Find row that matches the month and year
                    def row = rows.find {
                        it.request_year == monthYear.year && it.request_month == monthYear.month
                    }

                    // Aggregate demand
                    totalDemand += row?.quantity_demand ?: 0

                    [
                            dateKey       : "${monthYear?.month}/${monthYear?.year}",
                            year          : monthYear?.year,
                            month         : monthYear?.month,
                            monthName     : new DateFormatSymbols().months[monthYear?.month - 1],
                            quantityDemand: "${numberFormat.format(row?.quantity_demand ?: 0)}",
                    ]
                }


                data <<
                        [
                                dateKey       : "",
                                year          : "Average Monthly",
                                month         : "",
                                monthName     : "",
                                quantityDemand: "${numberFormat.format(totalDemand / numberOfDays * 30)}",
                        ]

                data <<
                        [
                                dateKey       : "",
                                year          : "Total Demand",
                                month         : "",
                                monthName     : "",
                                quantityDemand: "${numberFormat.format(totalDemand)}",
                        ]

            }
        }
        return data
    }


    def getMonths(Date startDate, Date endDate) {
        return (startDate..endDate).collect {
            [year: it[Calendar.YEAR], month: it[Calendar.MONTH] + 1]
        }.unique()
    }

}
