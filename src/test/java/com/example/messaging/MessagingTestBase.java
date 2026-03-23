package com.example.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.TestInfo;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
public abstract class MessagingTestBase {

    @BeforeEach
    void printTestHeader(TestInfo testInfo) {
        String name = testInfo.getDisplayName().replace("_", " ");
        System.out.printf("%n═══ %s ═══%n", name);
    }
}
