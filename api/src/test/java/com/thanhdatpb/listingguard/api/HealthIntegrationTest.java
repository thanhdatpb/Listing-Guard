package com.thanhdatpb.listingguard.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.web.HealthProbe;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** /health follows the probe: UP only while the database can be queried, 503 when it cannot. No API key either way. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class HealthIntegrationTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    HealthProbe probe;

    @Test
    void isUpWhenTheDatabaseAnswers() throws Exception {
        when(probe.isUp()).thenReturn(true);

        mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void is503WhenTheDatabaseDoesNotAnswer() throws Exception {
        when(probe.isUp()).thenReturn(false);

        mvc.perform(get("/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.reason").value("database unavailable"));
    }
}
