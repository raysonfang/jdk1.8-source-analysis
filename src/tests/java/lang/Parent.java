package tests.java.lang;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Parent {

    public final Logger log = LoggerFactory.getLogger(getClass());

    public void println(){
        log.info("Œ“ «£∫Papa");
    }
}
