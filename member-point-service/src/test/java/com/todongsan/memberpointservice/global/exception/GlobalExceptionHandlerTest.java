package com.todongsan.memberpointservice.global.exception;

import com.todongsan.memberpointservice.global.response.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @RestController
    static class TestController {

        @GetMapping("/test/custom")
        public ApiResponse<Void> throwCustom() {
            throw new CustomException(ErrorCode.POINT_INSUFFICIENT);
        }

        @GetMapping("/test/runtime")
        public ApiResponse<Void> throwRuntime() {
            throw new RuntimeException("예상치 못한 오류");
        }

        @PostMapping("/test/body")
        public ApiResponse<Void> readBody(@RequestBody Map<String, Object> body) {
            return ApiResponse.ok(null);
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void CustomException_발생시_해당_에러코드의_HTTP_상태_반환() throws Exception {
        mockMvc.perform(get("/test/custom").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("POINT_INSUFFICIENT"))
                .andExpect(jsonPath("$.message").value(ErrorCode.POINT_INSUFFICIENT.getMessage()))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void 예상치_못한_예외_발생시_500_반환() throws Exception {
        mockMvc.perform(get("/test/runtime").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"));
    }

    /** ERROR_POLICY.md 2-1: 요청 JSON 형식 오류는 400 INVALID_REQUEST (서버 오류가 아니다) */
    @Test
    void 요청_JSON을_읽을_수_없으면_400_INVALID_REQUEST() throws Exception {
        mockMvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }
}
