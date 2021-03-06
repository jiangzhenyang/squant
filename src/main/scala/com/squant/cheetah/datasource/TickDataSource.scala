package com.squant.cheetah.datasource

import java.io.{File, FileWriter}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date

import com.google.common.base.Strings
import com.squant.cheetah.Feeds
import com.squant.cheetah.domain.{MID, Symbol, Tick, TickType}
import com.squant.cheetah.engine.DataBase
import com.squant.cheetah.utils._
import com.squant.cheetah.utils.Constants._
import com.typesafe.scalalogging.LazyLogging

import scala.io.Source

/**
  * 由于tick数据量太大，在初始化数据时并不会更新全部tick数据，在请求csv数据不存在是会更新tick数据
  */
object TickDataSource extends DataSource with LazyLogging {

  private val baseDir = config.getString(CONFIG_PATH_DB_BASE)
  private val tickDir = config.getString(CONFIG_PATH_TICK)

  //初始化数据源
  override def init(taskConfig: TaskConfig =
                    TaskConfig("TickDataSource",
                      "", false, true, false, LocalDateTime.now, LocalDateTime.now)): Unit = {
    clear()
    update(taskConfig)
  }

  override def update(taskConfig: TaskConfig): Unit = {
    logger.info(s"Start to download stock tick data, ${format(taskConfig.stop, "yyyyMMdd")}")
    val stocks = Feeds.symbols()
    stocks.par.foreach(symbol => {
      if (taskConfig.clear) clear()
      if (taskConfig.toCSV) toCSV(symbol.code, taskConfig.stop)
      if (taskConfig.toDB) toDB(symbol.code, taskConfig.stop)
    })
    logger.info(s"Download completed")
  }

  override def clear(): Unit = {
    rm(s"$baseDir/$tickDir/${LocalDateTime.now.format(DateTimeFormatter.ofPattern("yyyyMMdd"))}")
      .foreach(r => logger.info(s"delete ${r._1} ${r._2}"))
  }

  def fromCSV(code: String, date: LocalDateTime) = {
    val file = new File(s"$baseDir/$tickDir/${date.format(DateTimeFormatter.ofPattern("yyyyMMdd"))}/$code.csv")

    if (!file.exists()) {
      toCSV(code, date)
    }

    val lines = Source.fromFile(file).getLines().drop(1)
    lines.map {
      line =>
        val fields = line.split(",", 6)
        Tick(LocalDateTime.parse(date.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + " " + fields(0), DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss")), fields(1).toFloat, fields(3).toInt, fields(4).toDouble, TickType.from(fields(5)))
    }.toSeq.reverse
  }

  def toCSV(code: String, date: LocalDateTime) = {
    val tickDayDataURL = "http://market.finance.sina.com.cn/downxls.php?date=%s&symbol=%s"
    val formatDate = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
    if (!new File(s"$baseDir/$tickDir/$formatDate").exists()) {
      new File(s"$baseDir/$tickDir/$formatDate").mkdirs()
    }
    val out = new FileWriter(s"$baseDir/$tickDir/$formatDate/$code.csv", false)
    out.write(Source.fromURL(String.format(tickDayDataURL, date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")), Symbol.getSymbol(code, tickDayDataURL)), "gbk").mkString.replaceAll("\t", ",")).ensuring {
      out.close()
      true
    }
  }

  def getTableName(code: String): String = {
    s"stock_tick_$code"
  }

  def toDB(code: String, date: LocalDateTime): Unit = {
    DataBase.getEngine.toDB(getTableName(code), fromCSV(code, date).toList.map(Tick.tickToRow(code, _)))
  }

  def fromDB(code: String, s: LocalDateTime, e: LocalDateTime): List[Tick] = {
    val rows = DataBase.getEngine.fromDB(getTableName(code), start = s, stop = e)
    rows.map(Tick.rowToTick)
  }

  def realTime(code: String): List[Tick] = {
    def sinaSymbol(symbol: String): String = {
      if (Strings.isNullOrEmpty(symbol) && symbol.length != 6) return ""
      if (symbol.startsWith("0") || symbol.startsWith("3")) return "sz" + symbol
      if (symbol.startsWith("6")) return "sh" + symbol
      return ""
    }

    val url = "http://vip.stock.finance.sina.com.cn/quotes_service/view/CN_TransListV2.php?num=10000&symbol=%s&rn=%s"
    val data = Source.fromURL(url.format(sinaSymbol(code), new Date().getTime)).mkString
    val regex = "(\\(.*\\))".r
    val today = format(TODAY, "yyyyMMdd")
    regex.findAllIn(data).map(string => {
      val fields = string.replaceAll("[(|)|'| ]", "").split(",")
      if (fields.length == 4)
        Tick(stringToLocalDateTime(today + fields(0), "yyyyMMddHH:mm:ss"),
          fields(2).toDouble,
          fields(1).toInt / 100,
          fields(1).toInt * fields(2).toDouble,
          TickType.from(fields(3))
        )
      else
        Tick(TODAY, 0, 0, 0, MID)
    }).toList.drop(1).reverse
  }

}