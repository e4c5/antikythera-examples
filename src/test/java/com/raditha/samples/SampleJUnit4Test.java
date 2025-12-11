package com.test.sample;

import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.Ignore;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(MockitoJUnitRunner.class)
public class SampleJUnit4Test {

    @Before
    public void setUp() {
        System.out.println("Setup");
    }

    @After
    public void tearDown() {
        System.out.println("Teardown");
    }

    @Test
    public void testWithMessage() {
        assertEquals("Values should be equal", 5, 2 + 3);
        assertTrue("Should be true", 1 < 2);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testExpectedException() {
        throw new IllegalArgumentException("Expected");
    }

    @Test(timeout = 1000)
    public void testWithTimeout() {
        // Should complete quickly
    }

    @Ignore("Not ready yet")
    @Test
    public void testIgnored() {
        // Ignored test
    }
}
