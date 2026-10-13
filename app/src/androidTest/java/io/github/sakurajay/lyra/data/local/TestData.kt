package io.github.sakurajay.lyra.data.local

/**
 * 仓库测试专用的数据库文件名。
 *
 * 这三个测试用 `deleteDatabase()` 删文件来断言"重新打开还在不在"。以前它们直接操作
 * 生产库 `lyra-library.db`——在模拟器上没事，**在真机上跑一次就把用户的收藏、自建歌单
 * 和收藏的歌单全清了**。测试一律走这份独立文件，生产库名不出现在测试里。
 */
const val TEST_DB_NAME = "lyra-library-test.db"
