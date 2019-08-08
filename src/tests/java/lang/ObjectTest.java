package tests.java.lang;

import org.junit.Test;

/**
 * Object class tests
 * 04 Object源码分析   Object类中的方法作用，以便理解
 *
 * @author raysonfang
 * @date 2019-8-8
 */
public class ObjectTest {

    public static void main(String[] args) {

    }

    /**
     * Object#getClass()
     */
    @Test
    public void testGetClass(){

        /**
         * 此方法是测试getClass()、.class 、getSuperclass()三者的区别
         * 运行结果为：
         * 当前运行类为:class tests.java.lang.Son
         * 通过class属性获取类的类对象：class tests.java.lang.Parent
         * 当前运行类的继承的父类为：class tests.java.lang.Parent
         */
        Son son = new Son();
        System.out.println("当前运行类为:" + son.getClass());
        System.out.println("通过class属性获取类的类对象：" + Parent.class);
        System.out.println("当前运行类的继承的父类为：" + son.getClass().getSuperclass());
    }
}
