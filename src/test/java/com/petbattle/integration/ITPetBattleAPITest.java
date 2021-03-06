package com.petbattle.integration;

import com.petbattle.containers.InfinispanTestContainer;
import com.petbattle.containers.KeycloakTestContainer;
import com.petbattle.containers.MongoTestContainer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.*;

import java.util.List;

import static com.petbattle.integration.APIMethods.*;
import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

@QuarkusTest
@DisplayName("API Test Cases")
@QuarkusTestResource(MongoTestContainer.class)
@QuarkusTestResource(InfinispanTestContainer.class)
@QuarkusTestResource(KeycloakTestContainer.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ITPetBattleAPITest {
    final String playerPayload = "username=player1&password=player1pwd&grant_type=password";
    final String adminPayload = "username=pbadmin&password=pbadminpwd&grant_type=password";

    @ConfigProperty(name = "quarkus.pbserver.test.secret")
    String clientSecret;

    @ConfigProperty(name = "quarkus.oidc.auth-server-url")
    String keycloakHost;

    private String adminToken = "";
    private String playerToken = "";

    @BeforeEach
    private void init() {
        if (!adminToken.isEmpty())
            return;

        String userResp = given()
                .contentType(URLENC)
                .auth()
                .preemptive()
                .basic("pbserver", clientSecret)
                .when()
                .body(playerPayload)
                .post(keycloakHost + "/protocol/openid-connect/token")
                .then()
                //                .log().all()
                .statusCode(200)
                .contentType(JSON)
                .body(notNullValue())
                .extract()
                .response().getBody().asString();

        this.playerToken = new JsonPath(userResp).get("access_token");

        String adminResp = given()
                .contentType(URLENC)
                .auth()
                .preemptive()
                .basic("pbserver", clientSecret)
                .when()
                .body(adminPayload)
                .post(keycloakHost + "/protocol/openid-connect/token")
                .then()
                .statusCode(200)
                .contentType(JSON)
                .body(notNullValue())
                .extract()
                .response().getBody().asString();

        this.adminToken = new JsonPath(adminResp).get("access_token");

        System.out.println("Configuration =>" + this.toString());
    }

    @Test
    @Order(1)
    @DisplayName("Test retrieval of OpenAPI defn")
    public void testGetOpenAPIDefn() {
        CallGetOpenAPIDefn();
    }

    @Test
    @Order(2)
    @DisplayName("Test Creation of a tournament with invalid auth")
    public void testNewTournamentEndpointInvalidAuth() {
        CallCreateTournamentInvalidAuth("invalidtokenANDIknowIT");
    }

    @Test
    @Order(3)
    @DisplayName("Test Creation of a tournament and then cancel it")
    public void testNewTournamentEndpoint() {
        String TID = CallCreateTournament(this.adminToken);
        CallCancelTournament(this.adminToken, TID);
        CallGetMetricsAndVerify("TournamentCreated_total 1.0");
        CallGetMetricsAndVerify("TournamentCancelled_total 1.0");
    }

    @Test
    @Order(4)
    @DisplayName("Test Creation of a tournament, start it, stop it , get the state and finally cancel it")
    public void testCreateStartStopStatusTournamentEndpoints() {
        String TID = CallCreateTournament(this.adminToken);
        CallGetTournamentState(this.playerToken, TID, "NotStarted");
        CallStartTournament(this.adminToken, TID);
        CallGetTournamentState(this.playerToken, TID, "Running");
        CallStopTournament(adminToken, TID);
        CallGetTournamentState(this.playerToken, TID, "Finished");
        CallCancelTournament(adminToken, TID);
        CallGetMetricsAndVerify("TournamentStatus_total 3.0");
        CallGetMetricsAndVerify("TournamentStopped_total 1.0");
    }

    @Test
    @Order(5)
    @DisplayName("Test Creation of a tournament, start it, stop it , get the state and finally cancel it")
    public void testCreateTournamentAddPetsEndpoints() {
        String TID = CallCreateTournament(this.adminToken);
        CallAddPet(this.adminToken, TID, "12345", 200);
        CallAddPet(this.adminToken, TID, "56789", 200);
        CallCancelTournament(adminToken, TID);
        CallGetMetricsAndVerify("TournamentPetsAdded_total{TID=\"" + TID + "\",} 2.0");
    }

    @Test
    @Order(6)
    @DisplayName("Test Creation of a tournament, start it, add a pet and finally cancel it")
    public void testCreateTournamentStartAddPetsEndpoints() {
        String TID = CallCreateTournament(this.adminToken);
        CallStartTournament(this.adminToken, TID);
        CallGetTournamentState(this.playerToken, TID, "Running");
        //We shouldn't be able to add a pet to a running tournament
        CallAddPet(this.adminToken, TID, "12345", 500);
        CallCancelTournament(adminToken, TID);
    }

    @Test
    @Order(7)
    public void testCreateStatusStartInvalidTournamentId() {
        String TID = CallCreateTournament(adminToken);

        given()
                .contentType(JSON)
                .auth()
                .preemptive()
                .oauth2(this.playerToken)
                .when()
                .get("/api/tournament/{tid}", "INVALIDTESTID")
                .then()
                .statusCode(500);

        given()
                .contentType(JSON)
                .auth()
                .preemptive()
                .oauth2(this.adminToken)
                .when()
                .put("/api/tournament/{tid}", "INVALIDTESTID")
                .then()
                .statusCode(500);

        given()
                .contentType(JSON)
                .auth()
                .preemptive()
                .oauth2(this.adminToken)
                .when()
                .delete("/api/tournament/{tid}", "INVALIDTESTID")
                .then()
                .statusCode(500);

        CallAddPet(this.adminToken, "INVALIDTESTID", "12345", 500);
        CallCancelTournament(adminToken, TID);
    }

    @Test
    @Order(8)
    public void testCreateTournamentStartAddPetsVoteEndpoints() {
        String TID = CallCreateTournament(adminToken);
        CallAddPet(this.adminToken, TID, "1", 200);
        CallAddPet(this.adminToken, TID, "2", 200);
        CallAddPet(this.adminToken, TID, "3", 200);
        CallAddPet(this.adminToken, TID, "4", 200);
        CallStartTournament(this.adminToken, TID);
        CallGetTournamentState(this.playerToken, TID, "Running");
        CallVote4Pet(this.playerToken, TID, "1", "up", 200);
        CallVote4Pet(this.playerToken, TID, "4", "down", 200);

        Response res = CallGetLeaderBoard(this.playerToken);

        //Stop the test
        CallStopTournament(adminToken, TID);

        Response res2 = CallGetLeaderBoard(this.playerToken);

        String json1 = res.asString();
        String json2 = res2.asString();

        assertThat(json1, equalTo(json2));

        //Cancel the tournament
        CallCancelTournament(adminToken, TID);
        CallGetMetricsAndVerify("TournamentPetsAdded_total{TID=\"" + TID + "\",} 4.0");
        CallGetMetricsAndVerify("GetLeaderboard_total 2.0");
    }

    @Test
    @Order(9)
    public void testValidateVoteEndpoint() {
        String TID = CallCreateTournament(adminToken);
        CallAddPet(this.adminToken, TID, "1", 200);
        CallStartTournament(this.adminToken, TID);
        CallGetTournamentState(this.playerToken, TID, "Running");
        CallVote4Pet(this.playerToken, TID, "1", "", 400);
        CallVote4Pet(this.playerToken, TID, "1", "fail", 400);
        CallVote4Pet(this.playerToken, TID, "1", "up", 200);
        CallVote4Pet(this.playerToken, TID, "1", "down", 200);
        CallCancelTournament(adminToken, TID);
        CallGetMetricsAndVerify("PetVotes_total{DIR=\"UP\",TID=\"" + TID + "\",} 1.0");
        CallGetMetricsAndVerify("PetVotes_total{DIR=\"DOWN\",TID=\"" + TID + "\",} 1.0");
    }

    @Test
    @Order(10)
    public void testLeaderboardEndpoints() {
        String TID = CallCreateTournament(adminToken);
        CallAddPet(this.adminToken, TID, "1", 200);
        CallAddPet(this.adminToken, TID, "2", 200);
        CallAddPet(this.adminToken, TID, "3", 200);
        CallAddPet(this.adminToken, TID, "4", 200);
        CallStartTournament(this.adminToken, TID);
        CallGetTournamentState(this.playerToken, TID, "Running");
        CallVote4Pet(this.playerToken, TID, "1", "up", 200);
        CallVote4Pet(this.playerToken, TID, "4", "up", 200);
        CallVote4Pet(this.playerToken, TID, "4", "up", 200);
        CallVote4Pet(this.playerToken, TID, "4", "up", 200);
        CallVote4Pet(this.playerToken, TID, "2", "up", 200);
        CallVote4Pet(this.playerToken, TID, "2", "up", 200);
        CallVote4Pet(this.playerToken, TID, "4", "up", 200);
        CallVote4Pet(this.playerToken, TID, "3", "Up", 200);
        CallVote4Pet(this.playerToken, TID, "3", "uP", 200);
        CallVote4Pet(this.playerToken, TID, "3", "UP", 200);

        Response res1 = CallGetLeaderBoard(this.playerToken);

        List<String> vote1 = res1.jsonPath()
                .getList("petId");

        assertThat("Pet 4 should be highest rated", vote1.get(0).equals("4"));
        assertThat("Pet 3 should be next rated", vote1.get(1).equals("3"));
        assertThat("Pet 2 should be next rated", vote1.get(2).equals("2"));
        assertThat("Pet 1 should be next rated", vote1.get(3).equals("1"));

        CallVote4Pet(this.playerToken, TID, "1", "up", 200);
        CallVote4Pet(this.playerToken, TID, "4", "down", 200);
        CallVote4Pet(this.playerToken, TID, "4", "doWN", 200);
        CallVote4Pet(this.playerToken, TID, "4", "DOwn", 200);
        CallVote4Pet(this.playerToken, TID, "2", "up", 200);
        CallVote4Pet(this.playerToken, TID, "2", "up", 200);
        CallVote4Pet(this.playerToken, TID, "3", "DOWN", 200);
        CallVote4Pet(this.playerToken, TID, "3", "up", 200);
        CallVote4Pet(this.playerToken, TID, "3", "up", 200);
        CallVote4Pet(this.playerToken, TID, "3", "up", 200);

        Response res2 = CallGetLeaderBoard(this.playerToken);

        List<String> vote2 = res2.jsonPath()
                .getList("petId");

        assertThat("Pet 3 should be highest rated", vote2.get(0).equals("3"));
        assertThat("Pet 2 should be next rated", vote2.get(1).equals("2"));
        assertThat("Pet 1 should be next rated", vote2.get(2).equals("1"));
        assertThat("Pet 4 should be next rated", vote2.get(3).equals("4"));

        //Cancel the tournament
        CallCancelTournament(adminToken, TID);

        CallGetMetricsAndVerify("PetVotes_total{DIR=\"UP\",TID=\"" + TID + "\",} 16.0");
        CallGetMetricsAndVerify("PetVotes_total{DIR=\"DOWN\",TID=\"" + TID + "\",} 4.0");
        CallGetMetricsAndVerify("TournamentPetsAdded_total{TID=\"" + TID + "\",} 4.0");
    }

    @Test
    @Order(11)
    @DisplayName("Test invoking liveness probe")
    public void testLivenessProbe() {
        CallGetLivenessProbe("\"status\": \"UP\"");
    }

    @Test
    @Order(12)
    @DisplayName("Test invoking readiness probe")
    public void testReadinessProbe() {
        CallGetReadinessProbe("\"status\": \"UP\"");
    }

    @Override
    public String toString() {
        return "ITPetBattleAPITest{" +
                "clientSecret='" + clientSecret + '\'' +
                ", keycloakHost='" + keycloakHost + '\'' +
                ", adminToken='" + adminToken + '\'' +
                ", playerToken='" + playerToken + '\'' +
                '}';
    }
}
