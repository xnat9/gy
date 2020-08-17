package service.fund

import cn.xnatural.enet.event.EL
import core.module.SchedSrv
import core.module.ServerTpl
import core.module.jpa.BaseRepo
import dao.entity.fund.Fund
import dao.entity.fund.FundHistory

import java.math.RoundingMode
import java.util.stream.Collectors

class FundAnalyzer extends ServerTpl {

    @Lazy def fundSrv = bean(FundSrv)
    @Lazy def repo    = bean(BaseRepo)
    @Lazy def sched   = bean(SchedSrv)


    @EL(name = 'sys.started', async = true)
    void start() {
        // 大降之后是否紧接着还至少有一次降()

//        for (int page = 1; ; page++) {
//            def p = repo.findPage(Fund, page, 100, {root, query, cb ->
//                cb.like(root.get('type'), "%股票%")
//            })
//            p.list.each {analyze(it.code)}
//            if (p.page >= p.totalPage) break
//        }
//        log.info("更新所有 股票相关类型 完成")
    }


    void analyze(String code) {
        up(code)
        // historyLowest(code)
        lastMonthAvg(code)
        spread(code)
        continuousDownOrUp(code)
        notify(code)
    }


    // 是否到了通知价
    void notify(String code) {
        def fund = repo.findById(Fund, code)
        if (fund.notifyUnitPrice == null) return
        if (fund.newestUnitPrice <= fund.notifyUnitPrice) {
            def hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (hour == 10 || hour == 14) {
                fundSrv.ddMsg("$code 到了通知价: $fund.notifyUnitPrice")
            }
        }
    }


    // 计算是否涨降
    void up(String code) {
        def fund = repo.findById(Fund, code)
        if (fund) {
            def latest = repo.find(FundHistory, { root, query, cb ->
                query.orderBy(cb.desc(root.get('date')))
                cb.equal(root.get('code'), code)
            })
            if (latest) {
                fund.up = fund.newestUnitPrice > latest.unitPrice
                repo.saveOrUpdate(fund)
            }
        }
    }


    @Deprecated
    void historyLowest(String code) {
        def fund = repo.findById(Fund, code)
        if (fund == null) return
        if (fund.minUnitPrice == null) {return}
        if (fund.type && (fund.type.contains('货币') || fund.type.contains('债'))) return

        if (fund.newestUnitPrice && fund.minUnitPrice && fund.newestUnitPrice - fund.minUnitPrice < 0.05D) {
            repo.saveOrUpdate(fund)
            String msg = "${fund.name + '('+ code + ')'} $fund.type 接近最历史低价"
            fundSrv.ddMsg(msg)
            ep.fire('wsMsg', msg)
        }
    }


    // 差价计算
    void spread(String code) {
        def fund = repo.findById(Fund, code)

        boolean f = fund.spread_newest_min == 0 // 是否为历史最低价
        if (fund.newestUnitPrice != null && fund.minUnitPrice != null) {
            fund.spread_newest_min = new BigDecimal(fund.newestUnitPrice - fund.minUnitPrice).setScale(4, RoundingMode.UP).doubleValue()
        }
        if (fund.spread_newest_min == 0 && !f && (fund.type in ['股票指数', '股票型', '混合型'] as Set) && Calendar.getInstance().get(Calendar.HOUR_OF_DAY) > 13) {
            String msg = "${fund.name + '('+ code + ')'} $fund.type 降到历史最低价"
            fundSrv.ddMsg(msg)
            ep.fire('wsMsg', msg)
        }

        if (fund.maxUnitPrice != null && fund.newestUnitPrice != null) {
            fund.spread_max_newest = new BigDecimal(fund.maxUnitPrice - fund.newestUnitPrice).setScale(4, RoundingMode.UP).doubleValue()
        }

        // TODO 下坡/上坡计算

        repo.saveOrUpdate(fund)
    }


    // 最近30天的 平均价
    void lastMonthAvg(String code) {
        def fund = repo.findById(Fund, code)

        fund.lastMonthAvg = repo.findPage(FundHistory, 1, 30, { root, query, cb ->
            query.orderBy(cb.desc(root.get('date')))
            cb.equal(root.get('code'), code)
        }).list.stream().filter{it != null && it.unitPrice != null}.map{it.unitPrice}.collect(Collectors.toList()).average()
        // 四舍五入
        fund.lastMonthAvg = new BigDecimal(fund.lastMonthAvg).setScale(4, RoundingMode.UP).doubleValue()
        repo.saveOrUpdate(fund)
    }


    // 连续 上升 or 下降
    void continuousDownOrUp(String code) {
        def fund = repo.findById(Fund, code)
        def p = repo.findPage(FundHistory, 1, 15, { root, query, cb ->
            query.orderBy(cb.desc(root.get('date')))
            cb.equal(root.get('code'), code)
        })

        fund.continuousDownCount = null
        fund.continuousUpCount = null
        def ls = p.list.stream().map{it.unitPrice}.filter{it != null}.collect(Collectors.toList())
        boolean up
        label: for (int i = 0; i < ls.size(); i++) {
            def v1 = ls[i]
            if (ls.size() > i + 1) {
                def v2 = ls[i+1]
                if (i == 0) {
                    if (v1 > v2) {
                        fund.continuousUpCount = 1
                        fund.continuousUpAmount = v1 - v2
                        up = true
                    } else if (v1 < v2) {
                        fund.continuousDownCount = 1
                        fund.continuousDownAmount = v2 - v1
                        up = false
                    } else break
                    continue label
                }

                if (up) {
                    if (v1 > v2) {
                        fund.continuousUpCount += 1
                        fund.continuousUpAmount += v1 - v2
                    }
                    else break label
                } else {
                    if (v1 < v2) {
                        fund.continuousDownCount += 1
                        fund.continuousDownAmount += v2 - v1
                    }
                    else break label
                }
            }
        }

        if (fund.continuousDownCount >= 3 && fund.continuousDownAmount > 0.05d && (fund.type in ['股票指数'] as Set) && !fund.up) {
            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            int minute = Calendar.getInstance().get(Calendar.MINUTE)
            if ((hour == 10 && minute > 40) || (hour == 14 && minute > 35)) {
                fundSrv.ddMsg("连续下降 $fund.continuousDownCount 次, $fund.name($fund.code)")
            }
            // TODO 连续3次 下降, 降>0.3
            // TODO 连续降>=4次 并且是周4 周5可能还会降, 等下个周1涨
            // TODO 大事发生: 例: 新型冠状病毒发生, 药相关一定涨
        }

        fund.downCountOfTen = 0
        ls.stream().reduce{v1, v2 ->
            if (v1 < v2) fund.downCountOfTen += 1
            v2
        }

        p = repo.findPage(FundHistory, 1, 30, { root, query, cb ->
            query.orderBy(cb.desc(root.get('date')))
            cb.equal(root.get('code'), code)
        })
        ls = p.list.stream().map{it.unitPrice}.filter{it != null}.collect(Collectors.toList())
        fund.downCountOf30day = 0
        ls.stream().reduce{v1, v2 ->
            if (v1 < v2) fund.downCountOf30day += 1
            v2
        }
        repo.saveOrUpdate(fund)
    }


    void 大降之后是否紧接着还至少有一次降() {
        // TODO
        int page = 1
        while (true) {
            def p = repo.findPage(Fund, page++, 50, { root, query, cb -> cb.equal(root.get('type'), '股票指数')})
            p.list.each {

            }
            if (p.page >= p.totalPage) break
        }
    }
}
