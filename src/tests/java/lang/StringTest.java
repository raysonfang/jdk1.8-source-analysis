package tests.java.lang;

import org.junit.Test;
import tests.base.BaseTest;

/**
 * 学习测试String类的源码案例
 *
 * @see tests.base.BaseTest 测试基类 主要放置一些测试公用的方法或变量，方便灵活测试
 */
public class StringTest extends BaseTest {

    @Test
    public void testHashcode() {
        String str = new String("123");
        log.info(""+str.hashCode());
    }
}
