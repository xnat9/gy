sys.name='gy'
sys.exec.corePoolSize=4

baseDir='/srv/gy'

web.port=8100

jpa {
    ds {
        jdbcUrl=url="jdbc:h2:$baseDir/h2/data"
    }
}

fileUploader {
    // 文件上传本地存放目录
    localDir="$baseDir/upload/"
    accessUrlPrefix="http://39.104.28.131:$http.port/file"
}

log.path="$baseDir/log"