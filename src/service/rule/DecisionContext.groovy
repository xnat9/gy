package service.rule

import cn.xnatural.enet.event.EP
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import service.rule.spec.DecisionSpec
import service.rule.spec.PolicySpec
import service.rule.spec.RuleSpec

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 决策执行上下文
 */
class DecisionContext {
    protected static final Logger    log             = LoggerFactory.getLogger(DecisionContext)
    // 执行上下文id
                    String           id
    // 开始时间
    final           Date             startup         = new Date()
                    DecisionSpec     ds
    protected       RuleSpec         curRuleSpec
    protected       PassedRule       curPassedRule
    protected       PolicySpec       curPolicySpec
    protected final List<PassedRule> passedRules     = new LinkedList<>()
    // 执行过的数据收集函数
    protected final Set<String>      appliedGetterFn = ConcurrentHashMap.newKeySet()
    // 最终决策结果
                    Decision         finalDecision
                    AttrManager      am
                    PolicyManger     pm
                    EP  ep
    // 运行状态. TODO 以后做暂停时 running == false
    protected final def running = new AtomicBoolean(false)
    // 是否已启动
    protected final def started = new AtomicBoolean(false)
    // 是否执行结束
    protected final def end     = new AtomicBoolean(false)
    // 输入参数
    Map                 input
    // 最终数据属性值
    @Lazy protected Map data    = new Data(this)
    // 执行迭代器. 迭代执行的策略和规则
    @Lazy protected def ittr    = new Itt(this)


    /**
     * 开始执行流程
     */
    void start() {
        if (!started.compareAndSet(false, true)) return
        log.info(logPrefix() + "开始")
        input?.forEach {k, v -> setAttr(k, v) }
        if (!ittr.hasNext()) {
            finalDecision = Decision.Reject
            log.warn(logPrefix() + "没有可执行的策略")
        }
        trigger()
    }


    /**
     * 触发执行流程
     */
    protected final void trigger() {
        if (end.get()) return
        if (!running.compareAndSet(false, true)) return
        try {
            while (running.get() && ittr.hasNext()) {
                def r = ittr.next()
                if (!r) break
                if (!r.enabled) continue
                finalDecision = decide(r)
                if (Decision.Reject == finalDecision || Decision.Review == finalDecision) break
            }
            if ((Decision.Reject == finalDecision || Decision.Review == finalDecision) || (!ittr.hasNext())) {
                end.set(true); finalDecision = finalDecision?:Decision.Accept
                running.set(false); curPolicySpec = null; curPassedRule = null; curRuleSpec = null
                log.info(logPrefix() + "结束成功. 共执行: " + (System.currentTimeMillis() - startup.getTime()) + "ms "  + result())
                ep?.fire("decision.end", this)
                end()
            }
        } catch (Exception ex) {
            end.set(true); finalDecision = Decision.Reject
            running.set(false); curPolicySpec = null; curPassedRule = null; curRuleSpec = null
            setAttr("errorCode", "EEEE")
            log.error(logPrefix() + "结束错误. 共执行: " + (System.currentTimeMillis() - startup.getTime()) + "ms " + result(), ex)
            ep?.fire("decision.end", this)
            end()
        }
    }


    // 执行结束
    protected void end() {}


    /**
     * 规则决策
     * @param RuleSpec 规则描述
     * @return Decision 决策结果
     */
    protected Decision decide(RuleSpec r) {
        if (!r.enabled) return null

        curRuleSpec = r
        curPassedRule = new PassedRule(name: r.规则名, customId: r.规则id); passedRules.add(curPassedRule)
        log.trace(logPrefix() + "开始执行规则")

        Decision decision
        try {
            def afterRejectFn
            r.decisionFn?.forEach((k, fn) -> {
                if ("Operate-after-Reject" == k) {afterRejectFn = fn; return}
                def d = fn(this.data)
                if ("Reject" == k) {
                    decision = d
                    log.trace(logPrefix() + "拒绝函数执行结果: " + d)
                } else if ("Accept" == k) {
                    decision = d
                    log.trace(logPrefix() + "通过函数执行结果: " + d)
                } else if ("Review" == k) {
                    decision = d
                    log.trace(logPrefix() + "人工审核函数执行结果: " + d)
                } else if ("Operate" == k) {
                    log.trace(logPrefix() + "操作函数执行完成")
                } else {
                    log.trace(logPrefix() + k + "函数执行完成. " + d)
                }
            })
            if (Decision.Reject == decision && afterRejectFn) afterRejectFn(this.data)
            log.debug(logPrefix() + "结束执行规则. decision: " + decision  + ", attrs: " + curPassedRule.attrs)
        } catch (Exception ex) {
            decision = Decision.Reject
            log.error(logPrefix() + "规则执行错误. 拒绝")
            throw ex
        } finally {
            curPassedRule.setDecision(decision); curRuleSpec = null; curPassedRule = null
        }
        return decision
    }


    /**
     * 获取属性
     * @param aName 属性名
     * @return 属性值
     */
    Object getAttr(String aName) {
        boolean f = curPassedRule?.attrs?.containsKey(aName)
        data.get(aName)
        if (!f) curPassedRule?.attrs?.remove(aName)
    }


    /**
     * 设置属性
     * @param aName 属性名
     * @param value 属性值
     */
    void setAttr(String aName, Object value) { data.put(aName, value) }


    /**
     * 设置为规则用到的属性
     * @param key
     * @param value
     */
    protected void ruleAttr(String key, Object value) {
        if (curPassedRule && !end.get()) curPassedRule.attrs.put(key, value)
    }


    /**
     * 规则执行迭代器
     */
    protected class Itt implements Iterator<RuleSpec> {
        final DecisionContext ctx

        Itt(DecisionContext ctx) {this.ctx = ctx}

        // 策略迭代器
        Iterator<PolicySpec> psIt = ctx.getDs().ps
            .stream()
            .map(s -> {
                def p = ctx.getPm().findPolicy(s)
                if (p == null) {
                    curPolicySpec = null
                    log.warn(ctx.logPrefix() + "未找到策略: " + s)
                }
                p
            }).filter(p -> p != null).iterator()
        Iterator<RuleSpec> rIt

        @Override
        boolean hasNext() { (rIt && rIt.hasNext()) || psIt.hasNext()}

        @Override
        RuleSpec next() {
            if ((ctx.curPolicySpec == null || (rIt == null || !rIt.hasNext())) && psIt.hasNext()) {
                ctx.curPolicySpec = psIt.next()
                log.debug(logPrefix() + "开始执行策略")
            }

            if (rIt == null || !rIt.hasNext()) rIt = ctx.curPolicySpec.rs.iterator()
            if (rIt.hasNext()) return rIt.next()
            return null
        }
    }


    /**
     * 数据存放. 用于规则闭包执行上下文
     */
    protected class Data extends ConcurrentHashMap<String, Object> {
        final DecisionContext ctx

        Data(DecisionContext ctx) {this.ctx = ctx}

        @Override
        Object get(Object key) {
            if (key == null) return null
            def r = super.get(key)
            if (r == null) {// 属性值未找到,则从属性管理器获取
                put(key, null) // 代表属性已从外部获取过,后面就不再去获取了
                if (!ctx.end.get()) {
                    def v = ctx.getAm().getAttr(key, ctx)
                    if (v instanceof Map) v.forEach((k, vv) -> put(k, vv))
                    else put(key, v)
                }
            }
            r = super.get(key)
            if (r instanceof Optional) {
                if (r.present) r = r.get()
                else r = null
            }
            ctx.ruleAttr(key, r)
            return r
        }

        @Override
        Object put(String key, Object value) {
            if (value == null) value = Optional.empty()
            else {
                if (value instanceof Optional) {
                    value = Optional.ofNullable(ctx.getAm().convert(value.orElseGet({null})))
                } else {
                    value = ctx.getAm().convert(key, value) // 属性值类型转换
                }
            }
            super.put(key, value)

            def n = ctx.getAm().alias(key)
            if (n && n!= key) super.put(n, value)

            ctx.ruleAttr(key, value)
            value
        }
    }


    /**
     * 执行过的规则
     */
    protected class PassedRule {
        String    name
        String    customId
        Decision  decision
        @Lazy Map<String, Object> attrs = new LinkedHashMap()

        @Override
        String toString() {
            return [name: name, customId: customId, decision: decision, attrs: attrs].toMapString()
        }
    }


    // 日志前缀
    protected String logPrefix() {
        "[${id? "$id, " :''}${ds.决策id? "决策: $ds.决策id" :''}${curPolicySpec? ", 策略: ${curPolicySpec.策略名}" :''}${curRuleSpec ? ", 规则: ${curRuleSpec.规则名}" :''}] -> "
    }


    /**
     * 整条决策 所有信息
     * @return
     */
    private Map summary
    Map summary() {
        if (this.summary && end.get()) return this.summary
        this.summary = [
            id: id, occurTime: startup.time,
            decision: finalDecision, decisionId: ds.决策id, input: input,
            attrs: data.collect {e ->
                 if (!e.key.matches("[a-zA-Z0-9]+")) return null
                 if (e.value instanceof Optional) {
                     return [e.key, e.value.orElseGet({null})]
                 }
                 return e
            }.findAll {it} .collectEntries(),
            passedRules: passedRules.collect {r ->
                [name: r.name, customId: r.customId, decision: r.decision, attrs: r.attrs.collectEntries {e ->
                    String k = e.key
                    def v = e.value
                    if (v instanceof Optional) {v = v.orElseGet({null})}
                    if (!k.matches("[a-zA-Z0-9]+")) {
                        k = getAm().alias(k)
                    }
                    return [k, v]
            }]}
        ]
        this.summary
    }


    /**
     * 用于接口返回
     * @return
     */
    Map result() {
        [id: id, decision: finalDecision, decisionId: ds.决策id,
         code: data.get('errorCode')?:'0000', // 错误码
         attrs: ds.returnAttrs.collectEntries { n ->
             def v = data.get(n)
             if (v instanceof Optional) {v = v.orElseGet({null})}
             if (n.matches("[a-zA-Z0-9]+")) return [n, v] // 取英文属性名
             else {
                 def en = am.alias(n)
                 if (en) return [en, v]
                 else [n, v]
             }
         }]
    }


    @Override
    String toString() { result().toString() }
}
