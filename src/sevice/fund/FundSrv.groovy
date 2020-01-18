package sevice.fund

import cn.xnatural.enet.event.EL
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import core.module.OkHttpSrv
import core.module.ServerTpl
import core.module.jpa.BaseRepo
import dao.entity.fund.Fund
import dao.entity.fund.FundHistory
import org.jsoup.Jsoup

import javax.annotation.Resource
import java.text.SimpleDateFormat

class FundSrv extends ServerTpl {

    @Resource OkHttpSrv http
    @Resource BaseRepo repo


    @EL(name = 'sys.started')
    def start() {
        http.shareCookie.put("api.fund.eastmoney.com", ["fundf10.eastmoney.com", "fund.eastmoney.com", "www.1234567.com.cn"])

//        http.get("http://www.1234567.com.cn").execute()
//        http.get("http://fund.eastmoney.com").execute()
//        http.get("http://fund.eastmoney.com/data/fundranking.html").execute()
//        http.get("http://fund.eastmoney.com/540008.html").execute()

        // Jsoup.connect("").get()

//        Jsoup.parse(
//            http.get("http://fundf10.eastmoney.com/jjjz_161024.html").execute()
//        ).select('a').each {
//            def href = it.attr('href')
//            if (href.startsWith("http")) {
//                try {
//                    http.get(href).execute()
//                } catch(Exception ex) {}
//            }
//        }

        // println http.get("http://api.fund.eastmoney.com/f10/lsjz?fundCode=161024&pageIndex=1&pageSize=20&startDate=&endDate=&_=" + System.currentTimeMillis()).execute()
        def rJo = JSON.parseObject(
            http.get("http://fund.eastmoney.com/data/rankhandler.aspx?op=ph&dt=kf&ft=all&rs=&gs=0&sc=zzf&st=desc&qdii=&tabSubtype=,,,,,&pi=1&pn=50&dx=1&v=0.09306765410098938")
                .param("ed", new SimpleDateFormat("yyyy-MM-dd").format(new Date()))
                .execute()
                .split("=")[1]
                .split(";")[0]
        )
        println(rJo.getJSONArray("datas")[0].class)
        println(rJo.getJSONArray("datas")[0])
        // initFundHistory('161024')
//        allFund()
//        println('Fund: ' + repo.count(Fund))
//        println('FundHistory: ' + repo.count(FundHistory))
    }

    protected allFund() {
        JSON.parseArray(
            http.get("http://fund.eastmoney.com/js/fundcode_search.js").execute().split("=")[1].split(";")[0]
        ).each { JSONArray ja ->
            def code = ja[0]
            if (repo.count(Fund, {root, query, cb -> cb.equal(root.get('code'), code)}) < 1) {
                try {
                    repo.trans {s ->
                        s.saveOrUpdate(new Fund(code: code, name: ja[2], type: ja[3]))
                        initFundHistory(code)
                    }
                } catch(Exception ex) {
                    log.error("code: {}" + code, ex)
                }
            }
        }
    }


    protected initFundHistory(String code) {
        if (repo.count(FundHistory, {root, query, cb -> cb.equal(root.get('code'), code)}) > 0) {
            log.info("Code '{}' history already exist", code)
            return
        }
        String r = http.get("http://fund.eastmoney.com/pingzhongdata/${code}.js").execute()
        repo.trans{s ->
            JSON.parseArray(r.split("Data_netWorthTrend")[1].split(";")[0].split("=")[1])
                .each { JSONObject jo->
                    s.saveOrUpdate(new FundHistory(code: code, unitPrice: Double.valueOf(jo['y']), date: new Date(Long.valueOf(jo['x']))))
                }
        }
    }
}
