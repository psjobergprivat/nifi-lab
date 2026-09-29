package org.mylabs.nifilab;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/** API tests without NiFi (lab.deploy-on-startup=false in the test profile). */
@QuarkusTest
class ScenarioResourceTest {

    @Test
    void listsScenariosAsNotDeployed() {
        given().get("/api/scenarios").then()
                .statusCode(200)
                .body("[0].id", equalTo("hello-flowfile"))
                .body("[0].deployment.state", equalTo("PENDING"));
    }

    @Test
    void scenarioDetailHasStepsAndLinks() {
        given().get("/api/scenarios/hello-flowfile").then()
                .statusCode(200)
                .body("input.port", equalTo(9101))
                .body("steps[0].component", equalTo("Receive message"))
                .body("links.provenance", startsWith("https://localhost:8443/nifi/#/provenance"));
    }

    @Test
    void unknownScenarioIs404WithMessage() {
        given().get("/api/scenarios/nope").then()
                .statusCode(404)
                .body("message", equalTo("Unknown scenario nope"));
    }

    @Test
    void startingAnUndeployedScenarioIsAConflict() {
        given().post("/api/scenarios/hello-flowfile/start").then().statusCode(409);
    }

    @Test
    void sinkAcceptsItemsWithEncodedAttributes() {
        String attributes = Base64.getEncoder().encodeToString(
                "{\"lab.trace.id\":\"t-1\",\"filename\":\"x\"}".getBytes(StandardCharsets.UTF_8));
        given().header("lab-attributes", attributes)
                .contentType("text/plain")
                .body("HELLO")
                .post("/api/sink/hello-flowfile/output")
                .then().statusCode(204);
    }
}
