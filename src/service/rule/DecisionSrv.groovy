package service.rule

import cn.xnatural.enet.event.EL
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.serializer.SerializerFeature
import core.OkHttpSrv
import core.ServerTpl
import core.jpa.BaseRepo
import dao.entity.DecisionResult

import java.time.Duration

/**
 * 决策Service
 */
class DecisionSrv extends ServerTpl {
    static final String SAVE_RESULT = 'save_result'

    @Lazy def http = bean(OkHttpSrv)
    @Lazy def repo = bean(BaseRepo, 'jpa_rule_repo')


    @EL(name = 'sys.starting', async = true)
    protected void init() {
        Long lastWarn // 上次告警时间
        queue(SAVE_RESULT)
            .failMaxKeep(getInteger(SAVE_RESULT + ".failMaxKeep", 10000))
            .errorHandle {ex, devourer ->
                if (lastWarn == null || (System.currentTimeMillis() - lastWarn >= Duration.ofSeconds(getLong(SAVE_RESULT + ".warnInterval", 60 * 5L)))) {
                    lastWarn = System.currentTimeMillis()
                    log.error("保存决策结果到数据库错误", ex)
                    ep.fire("globalMsg", "保存决策结果到数据库错误: " + (ex.message?:ex.class.simpleName))
                }
            }
    }


    /**
     * 系统全局消息
     * @param msg
     */
    @EL(name = 'globalMsg', async = true)
    void globalMsg(String msg) {
        log.info("系统消息: " + msg)
        ep.fire("wsMsg_rule", msg)
        String url = getStr('msgNotifyUrl')
        if (url) {
            //bean(OkHttpSrv)?.
        }
    }


    // 决策执行结果监听
    @EL(name = 'decision.end', async = true)
    void endDecision(DecisionContext ctx) {
        log.info("end decision: " + JSON.toJSONString(ctx.summary(), SerializerFeature.WriteMapNullValue))

        super.async { // 异步查询的, 异步回调通知
            if (Boolean.valueOf(ctx.input.getOrDefault('async', false).toString())) {
                String cbUrl = ctx.input['callback'] // 回调Url
                if (cbUrl && cbUrl.startsWith('http')) {
                    (1..2).each {
                        try {
                            http.post(cbUrl).jsonBody(JSON.toJSONString(ctx.result(), SerializerFeature.WriteMapNullValue)).execute()
                        } catch (ex) {
                            log.error("回调失败. id: " + ctx.id + ", url: " + cbUrl, ex)
                        }
                    }
                }
            }
        }

        queue(SAVE_RESULT) { // 保存决策结果到数据库
            repo.saveOrUpdate(
                repo.findById(DecisionResult, ctx.id).tap {
                    idNum = ctx.summary().get('attrs')['idNumber']?:ctx.input['idNumber']
                    status = ctx.status
                    exception = ctx.summary().get('exception')
                    decisionId = ctx.summary().get('decisionId')
                    decision = ctx.summary().get('decision')
                    spend = ctx.summary().get('spend')
                    input = JSON.toJSONString(ctx.input, SerializerFeature.WriteMapNullValue)
                    attrs = JSON.toJSONString(ctx.summary().get('attrs'), SerializerFeature.WriteMapNullValue)

                    def rs = ctx.summary().get('rules')
                    if (rs) rules = JSON.toJSONString(rs, SerializerFeature.WriteMapNullValue)

                    def dcr = ctx.summary().get('dataCollectResult')
                    if (dcr) dataCollectResult = JSON.toJSONString(dcr, SerializerFeature.WriteMapNullValue)
                }
            )
        }
    }
}