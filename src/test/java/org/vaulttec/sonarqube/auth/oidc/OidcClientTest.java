/*
 * OpenID Connect Authentication for SonarQube
 * Copyright (c) 2017 Torsten Juergeleit
 * mailto:torsten AT vaulttec DOT org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.vaulttec.sonarqube.auth.oidc;

import static junit.framework.TestCase.assertTrue;
import static org.assertj.core.api.Assertions.failBecauseExceptionWasNotThrown;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;

import javax.servlet.http.HttpServletRequest;

import com.nimbusds.openid.connect.sdk.UserInfoErrorResponse;
import com.nimbusds.openid.connect.sdk.UserInfoSuccessResponse;
import org.junit.Test;

import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.ErrorObject;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.TokenErrorResponse;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponse;
import com.nimbusds.openid.connect.sdk.claims.UserInfo;

public class OidcClientTest extends AbstractOidcTest {

	private OidcClient underTest = newSpyOidcClient();

	@Test
	public void getAuthenticationRequest() throws URISyntaxException {
		AuthenticationRequest request = underTest.getAuthenticationRequest(CALLBACK_URL, STATE);
		assertEquals("invalid scope", Scope.parse("openid email profile"), request.getScope());
		assertEquals("invalid client id", new ClientID("id"), request.getClientID());
		assertEquals("invalid state", new State(STATE), request.getState());
		assertEquals("invalid response type", ResponseType.getDefault(), request.getResponseType());
		assertEquals("invalid redirect uri", new URI(CALLBACK_URL), request.getRedirectionURI());
		assertEquals("invalid endpoint uri", new URI(ISSUER_URI).resolve("/protocol/openid-connect/auth"),
		    request.getEndpointURI());
	}

	@Test
	public void invalidAuthenticationRequestUri() {
		try {
			underTest.getAuthenticationRequest(INVALID_URL, STATE);
			failBecauseExceptionWasNotThrown(IllegalStateException.class);
		} catch (IllegalStateException e) {
			assertEquals("Creating new authentication request failed", e.getMessage());
		}
	}

	@Test
	public void getAuthorizationCode() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getMethod()).thenReturn("GET");
		when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
		when(request.getQueryString()).thenReturn("state=" + STATE + "&code=" + VALID_CODE);

		AuthorizationCode code = underTest.getAuthorizationCode(request);
		assertEquals("invalid access code", VALID_CODE, code.getValue());
	}

	@Test
	public void invalidAuthenticationResponseUri() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getMethod()).thenReturn("GET");
		when(request.getLocalAddr()).thenReturn("invalid . com");
		when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
		try {
			underTest.getAuthorizationCode(request);
			failBecauseExceptionWasNotThrown(IllegalStateException.class);
		} catch (IllegalStateException e) {
			assertEquals("Error while parsing callback request", e.getMessage());
		}
	}

	@Test
	public void authenticationErrorResponse() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getMethod()).thenReturn("GET");
		when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
		when(request.getQueryString())
		    .thenReturn("error=invalid_request&error_description=the request is not valid or malformed");
		try {
			underTest.getAuthorizationCode(request);
			failBecauseExceptionWasNotThrown(IllegalStateException.class);
		} catch (IllegalStateException e) {
			assertEquals("Authentication request failed: {\"error_description\":"
			    + "\"the request is not valid or malformed\",\"error\":\"invalid_request\"}", e.getMessage());
		}
	}

	@Test
	public void getUserInfo() {
		UserInfo userInfo = underTest.getUserInfo(new AuthorizationCode(VALID_CODE), CALLBACK_URL);
		assertEquals("john.doo", userInfo.getPreferredUsername());
		assertEquals("John Doo", userInfo.getName());
		assertEquals("John", userInfo.getGivenName());
		assertEquals("Doo", userInfo.getFamilyName());
		assertEquals("john.doo@acme.com", userInfo.getEmailAddress());
	}

	@Test
	public void tokenErrorResponse() {
		try {
			underTest.getUserInfo(new AuthorizationCode(INVALID_CODE), CALLBACK_URL);
			failBecauseExceptionWasNotThrown(IllegalStateException.class);
		} catch (IllegalStateException e) {
			assertEquals("Token request failed: {\"error\":\"invalid_request\"}", e.getMessage());
		}
	}

	@Test
	public void tokenErrorResponseWithoutErrorCode() {
		TokenErrorResponse errorTokenResponse = new TokenErrorResponse(new ErrorObject(null));
		doReturn(errorTokenResponse).when(underTest).getTokenResponse(new AuthorizationCode("no_error"), CALLBACK_URL);
		try {
			underTest.getUserInfo(new AuthorizationCode("no_error"), CALLBACK_URL);
			failBecauseExceptionWasNotThrown(IllegalStateException.class);
		} catch (IllegalStateException e) {
			assertEquals("Token request failed: No error code returned (identity provider not reachable - "
			    + "check network proxy setting 'http.nonProxyHosts' in 'sonar.properties')", e.getMessage());
		}
	}

	@Test
	public void invalidTokenRequestUri() {
		try {
			underTest.getUserInfo(new AuthorizationCode(VALID_CODE), INVALID_URL);
			failBecauseExceptionWasNotThrown(URISyntaxException.class);
		} catch (IllegalStateException e) {
			assertEquals("Retrieving access token failed", e.getMessage());
		}
	}

	@Test
	public void getUserInfoFromUserInfoEndpoint() throws java.text.ParseException, ParseException {
		OIDCTokenResponse tokenResponse = getValidTokenResponseWithoutProfileInformation();
		doReturn(tokenResponse).when(underTest).getTokenResponse(new AuthorizationCode(VALID_CODE), CALLBACK_URL);

		// user information response returned from user info endpoint
		UserInfoSuccessResponse userInfoResponse = new UserInfoSuccessResponse(new UserInfo(JSONObjectUtils.parse(
				"{\"sub\":\"e65c9607-fd4e-4bcd-97b1-ca057616590e\","
				+ "\"name\":\"John Doo\","
				+ "\"preferred_username\":\"john.doo\","
				+ "\"profile\":\"http://localhost:8080/hub/users/e65c9607-fd4e-4bcd-97b1-ca057616590e\","
				+ "\"email\":\"john.doo@acme.com\","
				+ "\"email_verified\":true}")));
		doReturn(userInfoResponse).when(underTest).getUserInfoResponse(tokenResponse.getOIDCTokens().getBearerAccessToken());

		doCallRealMethod().when(underTest).getUserInfo(new AuthorizationCode(VALID_CODE), CALLBACK_URL);

		UserInfo userInfo = underTest.getUserInfo(new AuthorizationCode(VALID_CODE), CALLBACK_URL);
		assertEquals("e65c9607-fd4e-4bcd-97b1-ca057616590e", userInfo.getSubject().getValue());
		assertEquals("john.doo", userInfo.getPreferredUsername());
		assertEquals("John Doo", userInfo.getName());
		assertEquals("http://localhost:8080/hub/users/e65c9607-fd4e-4bcd-97b1-ca057616590e", userInfo.getProfile().toString());
		assertEquals("john.doo@acme.com", userInfo.getEmailAddress());
		assertTrue(userInfo.getEmailVerified());
	}

	@Test
	public void userInfoErrorResponse() throws java.text.ParseException, ParseException {
		OIDCTokenResponse tokenResponse = getValidTokenResponseWithoutProfileInformation();
		doReturn(tokenResponse).when(underTest).getTokenResponse(new AuthorizationCode(VALID_CODE), CALLBACK_URL);

		UserInfoErrorResponse userInfoResponse = new UserInfoErrorResponse(new ErrorObject("some_error"));
		doReturn(userInfoResponse).when(underTest).getUserInfoResponse(tokenResponse.getOIDCTokens().getBearerAccessToken());

		doCallRealMethod().when(underTest).getUserInfo(new AuthorizationCode(VALID_CODE), CALLBACK_URL);

		try {
			underTest.getUserInfo(new AuthorizationCode(VALID_CODE), CALLBACK_URL);
			failBecauseExceptionWasNotThrown(IllegalStateException.class);
		} catch (IllegalStateException e) {
			assertEquals("UserInfo request failed: {\"error\":\"some_error\"}", e.getMessage());
		}
	}

	@Test
	public void userInfoErrorResponseWithoutErrorCode() throws java.text.ParseException, ParseException {
		OIDCTokenResponse tokenResponse = getValidTokenResponseWithoutProfileInformation();
		doReturn(tokenResponse).when(underTest).getTokenResponse(new AuthorizationCode(VALID_CODE), CALLBACK_URL);

		UserInfoErrorResponse userInfoResponse = new UserInfoErrorResponse(new ErrorObject(null));
		doReturn(userInfoResponse).when(underTest).getUserInfoResponse(tokenResponse.getOIDCTokens().getBearerAccessToken());

		doCallRealMethod().when(underTest).getUserInfo(new AuthorizationCode(VALID_CODE), CALLBACK_URL);

		try {
			underTest.getUserInfo(new AuthorizationCode(VALID_CODE), CALLBACK_URL);
			failBecauseExceptionWasNotThrown(IllegalStateException.class);
		} catch (IllegalStateException e) {
			assertEquals("UserInfo request failed: No error code returned "
					+ "(identity provider not reachable - check network proxy setting 'http.nonProxyHosts' in 'sonar.properties')", e.getMessage());
		}
	}

	private OidcClient newSpyOidcClient() {
		setSettings(true);
		OidcClient client = spy(new OidcClient(oidcSettings));
		try {
			OIDCTokenResponse validTokenResponse = OIDCTokenResponse.parse(JSONObjectUtils.parse(
			    "{\"access_token\":\"eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICJ3djY4UzUybDZTWVUxNGFfd0N3VElJT01WV1d1RXVXUFNBcERjYXo5Rnd3In0.eyJqdGkiOiIzMWNkOWM3YS05YTM3LTRiOTktOTViMC1jNzJlNGYzNGY4ODEiLCJleHAiOjE1MTQzMDcwNTQsIm5iZiI6MCwiaWF0IjoxNTE0MzA2NzU0LCJpc3MiOiJodHRwOi8vbWFjYm9vay1wcm8uZnJpdHouYm94OjgwODAvYXV0aC9yZWFsbXMvc3NvIiwiYXVkIjoic29uYXJxdWJlIiwic3ViIjoiYWZhYmE1OTItYWM4NS00Y2YxLThlYzYtMDA1OGQxNTdmODgyIiwidHlwIjoiQmVhcmVyIiwiYXpwIjoic29uYXJxdWJlIiwiYXV0aF90aW1lIjoxNTE0MzA2NzU0LCJzZXNzaW9uX3N0YXRlIjoiYWE2N2NjNjktN2EwNi00N2QxLWJhMDAtNjk2NDZlNjBiOGJlIiwiYWNyIjoiMSIsImFsbG93ZWQtb3JpZ2lucyI6WyJodHRwOi8vbWFjYm9vay1wcm8uZnJpdHouYm94OjgwODIvIl0sInJlYWxtX2FjY2VzcyI6eyJyb2xlcyI6WyJ1bWFfYXV0aG9yaXphdGlvbiJdfSwicmVzb3VyY2VfYWNjZXNzIjp7ImFjY291bnQiOnsicm9sZXMiOlsibWFuYWdlLWFjY291bnQiLCJtYW5hZ2UtYWNjb3VudC1saW5rcyIsInZpZXctcHJvZmlsZSJdfX0sIm5hbWUiOiJKb2huIERvbyIsInByZWZlcnJlZF91c2VybmFtZSI6ImpvaG4uZG9vIiwiZ2l2ZW5fbmFtZSI6IkpvaG4iLCJmYW1pbHlfbmFtZSI6IkRvbyIsImVtYWlsIjoiam9obi5kb29AYWNtZS5jb20ifQ.YElE-QodhPc8cUGo3jhT-phkmS3k_fHHDXhVm54m4wIZKDFeOnJD0spYkcODrIrOc04ibbinKJERtiBRxBF0P4RQq7NY08rgxFqt1STNrDb9tr4N_qEDXQ_66OUJKQIMd1L5yB5dzj73XAR1LRkhZSfVmDEGyE6A0x5rxgAeWCXUqMWOOq8Vq0ksdXiXeSdyg2n1XWU2j-uf6GB6mMtLXA0NddzQMOxPyhAKCGJRDJTwwb0fXzPeOVOvXO918rahsJ4iFn7wDnV2vaFBu37SNID7Iqmx3D_ptS2QrCdItg6nnK589BpcQMamTHINIQbkF-7LQH-U_yVJyEkOVrPzoQ\","
			        + "\"refresh_token\":\"eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICJ3djY4UzUybDZTWVUxNGFfd0N3VElJT01WV1d1RXVXUFNBcERjYXo5Rnd3In0.eyJqdGkiOiI3NzJkZTg1ZS1jNjcxLTQ0NDgtYTAwYS04ZjVkZTRkOWNlZTYiLCJleHAiOjE1MTQzMDg1NTQsIm5iZiI6MCwiaWF0IjoxNTE0MzA2NzU0LCJpc3MiOiJodHRwOi8vbWFjYm9vay1wcm8uZnJpdHouYm94OjgwODAvYXV0aC9yZWFsbXMvc3NvIiwiYXVkIjoic29uYXJxdWJlIiwic3ViIjoiYWZhYmE1OTItYWM4NS00Y2YxLThlYzYtMDA1OGQxNTdmODgyIiwidHlwIjoiUmVmcmVzaCIsImF6cCI6InNvbmFycXViZSIsImF1dGhfdGltZSI6MCwic2Vzc2lvbl9zdGF0ZSI6ImFhNjdjYzY5LTdhMDYtNDdkMS1iYTAwLTY5NjQ2ZTYwYjhiZSIsInJlYWxtX2FjY2VzcyI6eyJyb2xlcyI6WyJ1bWFfYXV0aG9yaXphdGlvbiJdfSwicmVzb3VyY2VfYWNjZXNzIjp7ImFjY291bnQiOnsicm9sZXMiOlsibWFuYWdlLWFjY291bnQiLCJtYW5hZ2UtYWNjb3VudC1saW5rcyIsInZpZXctcHJvZmlsZSJdfX19.Sqg5bqxEkpcg6r66fPW1X-RZvOMeKxHLl4Xk7S4BzGMiDNE8FlkbxW0JWUEm35oI3D0TVYv0B_MSFVc6mENBQeW3boJAtKUUCQy2FYKU4jta3KF-WLwKoTeU22ry-ZhRuJlydK-t0U3tB2ldWXTTfVI1qjHADIFt2RSggwhpU4iwZJiihxhk2KbVngClrNJ6Bk2olM276gopKzz9GN3erLXHZRtnzS3ZpyPvFzCoatP8v-FItAk01izToLbjyCjjicCBZfiMCw1_T0Zc1yz7l2kS0AE2kRBSDo58NggVL8yyXPhaLibigxYcIdawl9FpE3w5aiEquCH5WuQv5tt6LA\","
			        + "\"scope\":\"\","
			        + "\"id_token\":\"eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICJ3djY4UzUybDZTWVUxNGFfd0N3VElJT01WV1d1RXVXUFNBcERjYXo5Rnd3In0.eyJqdGkiOiIwYzdkNDQ0Yy1iM2MxLTQzM2YtODQ1OC1iYzRlYmQ4YjM4MGIiLCJleHAiOjE1MTQzMDcwNTQsIm5iZiI6MCwiaWF0IjoxNTE0MzA2NzU0LCJpc3MiOiJodHRwOi8vbWFjYm9vay1wcm8uZnJpdHouYm94OjgwODAvYXV0aC9yZWFsbXMvc3NvIiwiYXVkIjoic29uYXJxdWJlIiwic3ViIjoiYWZhYmE1OTItYWM4NS00Y2YxLThlYzYtMDA1OGQxNTdmODgyIiwidHlwIjoiSUQiLCJhenAiOiJzb25hcnF1YmUiLCJhdXRoX3RpbWUiOjE1MTQzMDY3NTQsInNlc3Npb25fc3RhdGUiOiJhYTY3Y2M2OS03YTA2LTQ3ZDEtYmEwMC02OTY0NmU2MGI4YmUiLCJhY3IiOiIxIiwibmFtZSI6IkpvaG4gRG9vIiwicHJlZmVycmVkX3VzZXJuYW1lIjoiam9obi5kb28iLCJnaXZlbl9uYW1lIjoiSm9obiIsImZhbWlseV9uYW1lIjoiRG9vIiwiZW1haWwiOiJqb2huLmRvb0BhY21lLmNvbSJ9.UwqM6TGPrpMpK70FKxX9ZQWyUySjx7fxeV5IAT2PtzTH4xZKLJQbQmb4uD9z7o5azK5fgYc9xQfJKQX2y2euz-mtSdjueqkPAY-djQEc2kyvb-4Nd9Qc4Uiy19aAuooNdM-pAiYhfvyQQiGMRe3z68sq45mgfDpKMBcV-5bOJNafQ8tLLEonzT37-1GMfuAMv7ppx4HmdUDQccZ0D4nBqmeFRPcA3BghPZJ6eThR_mRsuYW1yZDg5tMle2cZe80mnIZSTW349cPwJFfmQDNT7XQBHHTCa6pYsBoqs2KYadOnbMSPCXZ-agd0DzffgtujsBvrUWV8tXSZ7axY34xMQQ\","
			        + "\"token_type\":\"Bearer\",\"expires_in\":300}"));
			doReturn(validTokenResponse).when(client).getTokenResponse(new AuthorizationCode(VALID_CODE), CALLBACK_URL);

			TokenErrorResponse errorTokenResponse = TokenErrorResponse
			    .parse(JSONObjectUtils.parse("{\"error\":\"invalid_request\"}"));
			doReturn(errorTokenResponse).when(client).getTokenResponse(new AuthorizationCode(INVALID_CODE), CALLBACK_URL);

			UserInfo userInfo = new UserInfo(validTokenResponse.getOIDCTokens().getIDToken().getJWTClaimsSet());
			doReturn(userInfo).when(client).getUserInfo(new AuthorizationCode(VALID_CODE), CALLBACK_URL);
		} catch (ParseException | java.text.ParseException e) {
			// ignore
		}
		return client;
	}

	private OIDCTokenResponse getValidTokenResponseWithoutProfileInformation() throws java.text.ParseException, ParseException {

		// valid token response, but without profile related information
		return OIDCTokenResponse.parse(JSONObjectUtils.parse(
				"{\"id_token\":\"eyJhbGciOiJSUzI1NiIsImtpZCI6IjEifQ.eyJzdWIiOiJlNjVjOTYwNy1mZDRlLTRiY2QtOTdiMS1jYTA1NzYxNjU5MGUiLCJpc3MiOiJodHRwOi8vbG9jYWxob3N0OjgwODAvaHViIiwiYXVkIjpbIjYwZGNhY2FmLThhOTQtNDE3Ny1iMmYyLTEzNDg0NjNmODhjZSJdLCJleHAiOjEuNTIzNTcyMTY3NTYxRTksImlhdCI6MS41MTU3OTYxNjc1OTdFOSwiYXV0aF90aW1lIjoxLjUxNTc5NjE2NzU2MUU5fQ.o_h3f6QK--p1Ru8pUquoLpvB1vdBCorUfdq_I8J_yBbjyPS4LUP9-e_xkXtql6yOSh9AewNUb7PSKnJOq-TlMMMlOr-Or676i1wT0hGQb2aKnzzFu7VYQOep8_6t-AQSXRhckaR5NIJnF6oxFWdTwhizcenO_Osf12R-PQOyQsA\","
						+ "\"access_token\":\"1515799767598.60dcacaf-8a94-4177-b2f2-1348463f88ce.e65c9607-fd4e-4bcd-97b1-ca057616590e.0-0-0-0-0;1.MCwCFEjmjjDDL1yAQ+jYA+VxgYNNNr4hAhR66eAgXKfs6kOJehOALtRqw5wq9Q==\","
						+ "\"token_type\":\"Bearer\","
						+ "\"expires_in\":3600,"
						+ "\"scope\":\"0-0-0-0-0\"}"));
	}
}
