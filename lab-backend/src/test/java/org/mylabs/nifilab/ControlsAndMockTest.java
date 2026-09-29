package org.mylabs.nifilab;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/** Scenario controls and the mock service they drive (no NiFi needed). */
@QuarkusTest
class ControlsAndMockTest {

    private static final String SCENARIO = "errors-retry";

    @Test
    void detailShowsControlsWithDefaults() {
        given().get("/api/scenarios/" + SCENARIO).then()
                .statusCode(200)
                .body("controls.find { it.id == 'service.mode' }.options", equalTo(java.util.List.of("normal", "down", "reject-all")))
                .body("controlValues.'service.failure-rate'", equalTo("0"));
    }

    @Test
    void invalidControlValuesAreRejected() {
        given().contentType("application/json").body("{\"service.mode\": \"sometimes\"}")
                .put("/api/scenarios/" + SCENARIO + "/controls").then()
                .statusCode(400).body("message", containsString("must be one of"));
        given().contentType("application/json").body("{\"service.failure-rate\": \"150\"}")
                .put("/api/scenarios/" + SCENARIO + "/controls").then()
                .statusCode(400).body("message", containsString("between 0 and 100"));
        given().contentType("application/json").body("{\"no.such\": \"1\"}")
                .put("/api/scenarios/" + SCENARIO + "/controls").then()
                .statusCode(400);
    }

    @Test
    void mockServiceFollowsControlsAndCountsAttempts() {
        set("{\"service.mode\": \"normal\", \"service.failure-rate\": \"0\"}");
        call("t-ok").statusCode(200).body("attempt", equalTo(1));
        call("t-ok").statusCode(200).body("attempt", equalTo(2));

        given().header("lab.trace.id", "t-bad").body("{\"note\": \"invalid\"}")
                .post("/mock/" + SCENARIO + "/service").then().statusCode(400);

        set("{\"service.mode\": \"down\"}");
        call("t-down").statusCode(503).body("result", equalTo("service is down"));
        set("{\"service.mode\": \"normal\"}");
    }

    private static void set(String json) {
        given().contentType("application/json").body(json)
                .put("/api/scenarios/" + SCENARIO + "/controls").then().statusCode(200);
    }

    private static io.restassured.response.ValidatableResponse call(String traceId) {
        return given().header("lab.trace.id", traceId).body("{\"paymentId\": \"p\"}")
                .post("/mock/" + SCENARIO + "/service").then();
    }
}
