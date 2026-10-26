package com.javaee.file;

import com.javaee.fileservice.FileServiceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

class FileApplicationTests {

    @Test
    void contextLoads() {
        org.junit.jupiter.api.Assertions.assertNotNull(
                FileServiceApplication.class.getAnnotation(SpringBootApplication.class));
    }

}
