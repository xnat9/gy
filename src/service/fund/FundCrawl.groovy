package service.fund

import cn.xnatural.enet.event.EL
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONException
import com.alibaba.fastjson.JSONObject
import core.Utils
import core.module.OkHttpSrv
import core.module.SchedSrv
import core.module.ServerTpl
import core.module.jpa.BaseRepo
import dao.entity.fund.Fund
import dao.entity.fund.FundHistory
import org.apache.commons.lang3.time.DateUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

import java.text.ParseException
import java.text.SimpleDateFormat

class FundCrawl extends ServerTpl {

    @Lazy def http       = bean(OkHttpSrv)
    @Lazy def repo       = bean(BaseRepo)
    @Lazy def analyzer   = bean(FundAnalyzer)
    @Lazy def ignoreCode = new HashSet<String>()


    @EL(name = 'sys.started', async = true)
    void start() {
        http.shareCookie.put("api.fund.eastmoney.com", ["fundf10.eastmoney.com", "fund.eastmoney.com", "www.1234567.com.cn"])

        for(int page=1; ; page++) {
            def p = repo.findPage(Fund, page++, 100, { root, query, cb -> cb.equal(root.get('available'), false)})
            p.list.each {ignoreCode.add(it.code)}
            if (p.page >= p.totalPage) break
        }
        log.info("加载忽略更新的代码个数: {}", ignoreCode.size())
        updateFunds()
    }


    // 更新fund数据
    void updateFunds() {
        String msg = "开始全量更新"
        ep.fire('wsMsg', msg)
        log.info(msg)
        allFunds2()
        allFunds1()
        msg = "全量更新完成"
        ep.fire('wsMsg', msg)
        log.info(msg)
    }


    // 根据Fund code 更新其相关信息
    void updateFund(String code, boolean force = false) {
        if (!force) { // 不强制更新, 则智能过虑不需要更新的
            if (ignoreCode.contains(code)) return
            def fund = repo.findById(Fund, code)

            if (fund && (!fund.type.contains('股票') || fund.spread_max_newest != null)) {
                Calendar cal = Calendar.instance
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.HOUR_OF_DAY, 15)
                def date_15 = cal.getTime() // 下午3点
                cal.set(Calendar.HOUR_OF_DAY, 9)
                def date_9 = cal.getTime() // 上午9点
                cal.set(Calendar.HOUR_OF_DAY, 0)
                def yesterday = cal.getTime().time - 1000L * 60 * 60 * 24  // 昨天0点

                // 大于昨天. 下午3点之后到第二天9点之前只更新一次
                // if (fund.updateTime.time > yesterday && (fund.updateTime.time > date_15.time || fund.updateTime.time < date_9.time)) return

                // 上次更新距离现在不超过1小时, 则不更新
                // if ((System.currentTimeMillis() - fund.updateTime.time < 60 * 60 * 1000L)) return
            }
        }
        try {
            updateFundInfo(code)
            updateFundHistory(code)

            def fund = repo.findById(Fund, code)
            if (fund.maxUnitPrice == null || fund.minUnitPrice == null) updateMinMax(code)
            if (force) {
                updateNewestPrice(code)
                analyzer.analyze(code)
            }
            else async{analyzer.analyze(code)}
        } catch (Throwable ex) {
            String msg = "Update Fund ' " + code + "' error. " + (ex.message?:ex.cause?.message?:'')
            ep.fire('wsMsg', msg)
            log.error(msg, ex)

            def fund = repo.findById(Fund, code)
            if (fund == null) {fund = new Fund(code: code, available: false)}
            else fund.available = false
            // repo.saveOrUpdate(fund)
        }
        def fund = repo.findById(Fund, code)
        if (fund != null && fund.available == false) ignoreCode.add(code)
        if (!force) Thread.sleep(1000L * new Random().nextInt(3))
    }


    protected void allFunds2() {
        def rJo = JSON.parseObject(
            http.get("http://fund.eastmoney.com/data/rankhandler.aspx?op=ph&dt=kf&ft=all&rs=&gs=0&sc=zzf&st=desc&qdii=&tabSubtype=,,,,,&pi=1&pn=50&dx=1&v=0.09306765410098938")
                .param("ed", new SimpleDateFormat("yyyy-MM-dd").format(new Date()))
                .debug()
                .execute()
                .split("=")[1]
                .split(";")[0]
        )
        rJo.getJSONArray("datas").each {String s ->
            def arr = s.split(",")
            String code = arr[0]
            // String name = arr[1]
            // String startDateStr = arr[16]
            try {
                // updateFund(code)
                updateFundInfo(code); Thread.sleep(100L * new Random().nextInt(20))
            } catch (Throwable ex) {
                log.error("updateFund error", ex)
            }
        }
    }


    protected void allFunds1() {
        JSON.parseArray(
            http.get("http://fund.eastmoney.com/js/fundcode_search.js").debug().execute().split("=")[1].split(";")[0]
        ).each { JSONArray ja ->
            def code = ja[0]
            try {
                updateFundInfo(code); Thread.sleep(100L * new Random().nextInt(20))
                // updateFund(code)
            } catch (Throwable ex) {
                log.error("updateFund error", ex)
            }
        }
    }


    // 更新 最小最大单价
    protected void updateMinMax(String code) {
        def fund = repo.findById(Fund, code)
        if (fund == null) return
        def arr = repo.trans{s ->
            s.createQuery("select min(unitPrice) as minPrice, max(unitPrice) as maxPrice from FundHistory where code =: code")
                .setParameter("code", code)
                .singleResult
        }
        if (arr[0] != null) fund.setMinUnitPrice(Double.valueOf(arr[0]))
        if (arr[1] != null) fund.setMaxUnitPrice(Double.valueOf(arr[1]))
        repo.saveOrUpdate(fund)
    }


    /**
     * 更新历史数据
     * @param code
     * @return
     */
    int updateFundHistory(String code) {
        int count = 0
        Throwable ex
        try {
            count = updateFundHistory2(code)
            // updateFundHistory1(code)
        } catch(Throwable t) {
            ex = t
            log.error("更新历史记录出错. " + code, ex)
        }
        def fund = repo.findById(Fund, code)
        if (count > 0) {
            log.info("新增历史记录 '{}' {} 条", (fund.name + "($fund.code)"), count)
            updateMinMax(code)
            async{analyzer.analyze(code)}
        }
        if (count > 0 || ex) {
            ep.fire('wsMsg', "$fund.name($fund.code) 更新历史记录${ex ? "错误. $ex.message?:${ex.cause?.message}?:''" : "完成. 共计 $count 条"}")
        }
        count
    }


    void updateFundInfo(String code) {
        updateFundInfo1(code)
        // updateFundInfo2(code)
    }


    /**
     * 参考: http://fundf10.eastmoney.com/000009.html
     * @param code
     */
    protected updateFundInfo2(String code) {
        // TODO
    }


    /**
     * 更新
     * 参考: http://fund.eastmoney.com/000009.html
     * @param code
     * @return
     */
    protected updateFundInfo1(String code) {
        def doc = Jsoup.parse(http.get("http://fund.eastmoney.com/${code}.html").execute())
        String name
        String type
        Double total
        Double newest
        Date start
        String desc
        Boolean available = true
        Double lowestPurchase
        try {
            name = doc.select('.SitePath a').find {it.attr('href').contains(code + ".html")}?.text()
            newest = Double.valueOf(doc.select('.fundInfoItem .dataItem01 .dataNums span')[0].text())
            def tbEl = doc.select('.fundInfoItem tbody')
            type = tbEl.select('tr')[0].select('td')[0].select('a').text()
            total = Double.valueOf(tbEl.select('tr')[0].select('td')[1].text().split("亿元")[0].split("：")[1])
            start = new SimpleDateFormat("yyyy-MM-dd").parse(tbEl.select('tr')[1].select('td')[0].text().split("：")[1])

            def el = doc.select('#moneyAmountTxt')
            if (el.size() > 0) lowestPurchase = Double.valueOf(el.attr('data-minsg'))
        } catch (Exception ex) {
            if (doc.head().toString().contains('location.href')) {
                available = false
                desc = doc.head().selectFirst('script').html().replace('location.href = ', '')
            } else {
                def els = doc.select('.fundInfoItem div')
                if (els.size() > 0) desc = els[0].text()
                else throw new RuntimeException("未知错误", ex)
            }
        }

        def fund = repo.findById(Fund, code)
        if (fund == null) {
            fund = repo.saveOrUpdate(new Fund(lowestPurchase: lowestPurchase, available: available, code: code, name: name, start: start, total: total, type: type, newestUnitPrice: newest, comment: desc))
            String msg = "新增. $fund.name($fund.code), $fund.type, ${fund.total + "亿"}"
            // fundSrv.ddMsg(msg)
            ep.fire('wsMsg', msg)
            log.info(msg)
        } else {
            fund.total = total
            fund.type = type
            fund.name = name
            fund.newestUnitPrice = newest
            fund.comment = desc
            fund.available = available
            fund.lowestPurchase = lowestPurchase
            if (fund.start == null) fund.start = start
            repo.saveOrUpdate(fund)
            log.info("更新Fund: {}", Utils.toMapper(fund)
                .addConverter('start', {v -> new SimpleDateFormat('yyyy-MM-dd').format(v)})
                .addConverter('createTime', {v -> new SimpleDateFormat('yyyy-MM-dd HH:mm:ss').format(v)})
                .addConverter('updateTime', {v -> new SimpleDateFormat('yyyy-MM-dd HH:mm:ss').format(v)})
                .sort()
                .build())
        }
    }


    // 更新最新价
    def updateNewestPrice(String code) {
        String s = http.get("http://fundgz.1234567.com.cn/js/${code}.js?rt=" + System.currentTimeMillis()).debug().execute()
        def p = JSON.parseObject(s.replace("jsonpgz(", "").replace(");", "")).getDouble("gsz")
        def fund = repo.findById(Fund, code)
        fund.newestUnitPrice = p
        repo.saveOrUpdate(fund)
    }


    // 参考文章: https://www.jianshu.com/p/d79d3cd62560
    protected updateFundHistory1(String code) {
//        if (repo.count(FundHistory, {root, query, cb -> cb.equal(root.get('code'), code)}) > 0) {
//            log.info("Code '{}' history already exist", code)
//            return
//        }
        String r = http.get("http://fund.eastmoney.com/pingzhongdata/${code}.js").execute()
        repo.trans{s ->
            JSON.parseArray(r.split("Data_netWorthTrend")[1].split(";")[0].split("=")[1])
                .each { JSONObject jo->
                    s.saveOrUpdate(new FundHistory(code: code, unitPrice: Double.valueOf(jo['y']), date: new Date(Long.valueOf(jo['x']))))
                }
        }
    }


    // 参考文章: https://blog.csdn.net/weizhixiang/article/details/51445054
    protected int updateFundHistory2(String code) {

        def fund = repo.findById(Fund, code)
        if (fund == null) throw new Exception("Fund '$code' not exist")
        int startPage = 1
        def latest = repo.find(FundHistory, { root, query, cb -> query.orderBy(cb.desc(root.get('date'))).where(cb.equal(root.get('code'), code))})
        if (latest) {
            def sdf = new SimpleDateFormat("yyyy-MM-dd")
            def today = sdf.parse(sdf.format(new Date()))
            long t = latest.date
            while (today > t) {
                t = DateUtils.addDays(t, 1)
                startPage++
            }
        }

        int count = 0
        def fn = {String s ->
            if (s.contains('暂无数据')) return false
            JSONObject jo
            try {
                def s1 = s.split("apidata=")[1]
                if (s1.endsWith(";")) s1 = s1.substring(0, s1.length() - 1)
                jo = JSON.parseObject(s1)
            } catch (JSONException ex) {
                throw ex
            }

            for (def it = Jsoup.parse(jo['content']).select("tbody tr").iterator(); it.hasNext(); ) {
                Element el = it.next()
                Date date
                try {
                    date = new SimpleDateFormat("yyyy-MM-dd").parse(el.select('td')[0].text().replace("\\*", ''))
                } catch(ParseException ex) {
                    throw new Exception("日期格式化错误. \n" + el.select('td')[0].html(), ex)
                }
                Double unitPrice
                try {
                    def priceStr = el.select('td')[1].text()
                    if (priceStr != null && priceStr != '') unitPrice = Double.valueOf(priceStr)
                } catch (NumberFormatException ex) {
                    throw new Exception("单价格式化错误. \n" + el.select('td')[1].html(), ex)
                }
                def e = repo.find(FundHistory, { root, query, cb -> cb.and(cb.equal(root.get("code"), code), cb.equal(root.get("date"), date))})
                if (e == null) {
                    e = repo.saveOrUpdate(new FundHistory(code: code, unitPrice: unitPrice, date: date))
                    count++
                }
            }
            if (jo.getInteger('curpage') >= jo.getInteger('pages')) { // 是否是最后一页
                return false
            }
            true
        }

        boolean flag = true
        int page = startPage
        while (flag) {
            flag = fn.call(
                http.get("http://fund.eastmoney.com/f10/F10DataApi.aspx?type=lsjz&code=$code&page=${page++}&per=20").debug().execute()
            )
        }
        count
    }
}
