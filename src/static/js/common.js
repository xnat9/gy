// web socket
function ws(cfg) {
    cfg = cfg || {};
    // web socket
    let client = window.client;
    if (client && (client.readyState == 1 || client.readyState == 2)) {
        console.log('Websocket连接可用不用重创建. state: ' + client.readyState);
        return;
    }
    function doCreate() {
        console.log('create websocket ...');
        try {
            let protocol = "ws://";
            if (window.location.protocol.startsWith("https")) protocol = "wss://";
            client = new WebSocket(protocol + window.location.host + (cfg.path || "/test/msg"));
            window.client = client;
        } catch (e) {
            console.log('创建websocket错误', e);
            setTimeout(function () {
                ws(cfg);
            }, cfg.reconnection || (1000 * 60 * 2)); // 每两分钟重试
            return
        }
        client.onclose = cfg.onClose || function () {
            setTimeout(function () {
                ws(cfg)
            }, cfg.reconnection || (1000 * 60 * 2)); // 每两分钟重试
        };
        client.onmessage = cfg.onMsg || function (e) { //接收websocket 消息
            let jo = toJSON(e.data);
            if (jo) {
                // {type: 'xxx': data: null}
                // if (jo.type == 'xxx') {}
                if (jo.data.msg) {
                    app.$Notice.info({title: '后台提示', content: jo.data.msg, duration: 5})
                }
            } else {
                app.$Notice.info({title: '后台提示', content: e.data, duration: 7})
            }
        };
        client.onopen = cfg.onOpen || function() {
            console.log('websocket onopen');
            client.send('成功连接...');
        };
    }
    doCreate()
}

function toJSON(str) {
    if (typeof str == 'string') {
        try {
            let obj = JSON.parse(str);
            return obj
        } catch(e) {}
    }
    return null
}


// 异步加载全局js库
let jsMap = new Map([
    ['moment', 'js/lib/moment.min.js'],
]);
function loadJs() {
    if (arguments.length < 1) return;
    let names = [...arguments];
    let cb = arguments[arguments.length - 1]; // 最后一个参数可为 回调函数
    if (typeof cb == "string") {
        cb = null
    } else {
        names.pop()
    }
    let length = names.length;
    names.forEach(((value, index) => {
        let path = jsMap.get(value);
        if (!path) {
            if (cb) cb();
            return;
        }
        $.ajax({
            url: path,
            success: (res) => {
                let script = document.createElement( "script" );
                script.text = res;
                document.head.appendChild(script).parentNode.removeChild(script);
                length--; jsMap.delete(value); //只加载一次
                if (length == 0 && cb) cb();
            }
        })
    }));
}


//时间戳格式化组件
Vue.component('date-item', (resolve, reject) => {
    loadJs('moment', () => {
        resolve({
            props: ['time', 'format'],
            template: '<span>{{timeStr}}</span>',
            computed: {
                timeStr: function () {
                    if (!this.time) return '';
                    if (this.format) return moment(this.time).format(this.format);
                    else return moment(this.time).format('YYYY-MM-DD HH:mm:ss')
                }
            }
        })
    })
});


// 异步加载组件
new Map([
    ['Upload', 'Upload.vue'],
]).forEach((value, key) => {
    Vue.component(key, (resolve, reject) => {
        httpVueLoader('components/'+ value)().then((r) => resolve(r))
    });
});
