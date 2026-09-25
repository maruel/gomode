// Tests for the OAuth 2.0 client-credentials grant (RFC 6749 §4.4), the
// authorization backend of the official MCP OAuth Client Credentials extension.

package oauthserver

import (
	"crypto/rsa"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/maruel/gomode/oauth"
)

func clientCredentialsTestClient(t *testing.T, grantTypes []string) (Client, *rsa.PrivateKey) {
	t.Helper()
	clientID := "https://client.example/client.json"
	key, jwk := testDPoPRSAKeyPair(t)
	jwk.Kid = "client-signing-key"
	jwk.Alg = oauth.JWTAlgRS256
	jwk.Use = "sig"
	client := Client{
		ID:                      clientID,
		Name:                    "Machine Client",
		TokenEndpointAuthMethod: oauth.TokenEndpointAuthPrivateKeyJWT,
		GrantTypes:              grantTypes,
		Provenance:              ClientProvenanceMetadata,
		JWKS:                    []oauth.JWK{*jwk},
	}
	return client, key
}

func postClientCredentialsForm(t *testing.T, h http.Handler, path string, form url.Values) *httptest.ResponseRecorder {
	t.Helper()
	request := httptest.NewRequestWithContext(t.Context(), http.MethodPost, path, strings.NewReader(form.Encode()))
	request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	response := httptest.NewRecorder()
	h.ServeHTTP(response, request)
	return response
}

func TestClientCredentialsGrant(t *testing.T) {
	t.Parallel()

	t.Run("valid issues machine-to-machine token", func(t *testing.T) {
		t.Parallel()
		client, key := clientCredentialsTestClient(t, []string{oauth.GrantClientCredentials})
		server := newTestServer(t)
		handler := newTestServerHandler(server)
		seedClientMetadataCache(server, &client)
		response := postClientCredentialsForm(t, handler, "/oauth/token", url.Values{
			"grant_type":            {oauth.GrantClientCredentials},
			"client_id":             {client.ID},
			"scope":                 {"read write"},
			"resource":              {testResourceURL},
			"client_assertion_type": {clientAssertionType},
			"client_assertion":      {makeClientAssertion(t, key, "client-signing-key", client.ID, "cc-jti-1", time.Now())},
		})
		if response.Code != http.StatusOK {
			t.Fatalf("client_credentials grant = %d %s", response.Code, response.Body.String())
		}
		var token oauth.TokenResponse
		if err := json.NewDecoder(response.Body).Decode(&token); err != nil {
			t.Fatalf("decode token response: %v", err)
		}
		if token.RefreshToken != "" {
			t.Fatalf("client_credentials issued a refresh token: %s", response.Body.String())
		}
		if token.Scope != "read write" || token.AccessToken == "" {
			t.Fatalf("token response = %+v", token)
		}
		if len(server.state.Grants) != 1 {
			t.Fatalf("stored grants = %d, want 1", len(server.state.Grants))
		}
		for _, grant := range server.state.Grants {
			if grant.UserID != "" || grant.ClientID != client.ID || grant.Scope != "read write" {
				t.Fatalf("stored grant = %+v", grant)
			}
		}
		// The token verifies without any user session; its subject is the client.
		claims, err := server.verifyBearer(newTestResourceRequest(t), token.AccessToken)
		if err != nil {
			t.Fatalf("verifyBearer: %v", err)
		}
		if claims.Subject != client.ID || claims.ClientID != client.ID || claims.User != (oauth.User{}) || strings.Join(claims.Scopes, " ") != "read write" {
			t.Fatalf("bearer claims = %+v", claims)
		}
		server.Close()
	})

	t.Run("public client is rejected", func(t *testing.T) {
		t.Parallel()
		server := newTestServer(t)
		handler := newTestServerHandler(server)
		registered := registerOAuthTestClient(t, handler, "Public Client", []string{"https://client.example/callback"})
		response := postClientCredentialsForm(t, handler, "/oauth/token", url.Values{
			"grant_type": {oauth.GrantClientCredentials},
			"client_id":  {registered.ClientID},
			"scope":      {"read"},
		})
		if response.Code != http.StatusBadRequest || !strings.Contains(response.Body.String(), "unauthorized_client") {
			t.Fatalf("public client grant = %d %s", response.Code, response.Body.String())
		}
		server.Close()
	})

	t.Run("confidential client without the grant is rejected", func(t *testing.T) {
		t.Parallel()
		client, key := clientCredentialsTestClient(t, []string{oauth.GrantAuthorizationCode})
		client.RedirectURIs = []string{"https://client.example/callback"}
		server := newTestServer(t)
		handler := newTestServerHandler(server)
		seedClientMetadataCache(server, &client)
		response := postClientCredentialsForm(t, handler, "/oauth/token", url.Values{
			"grant_type":            {oauth.GrantClientCredentials},
			"client_id":             {client.ID},
			"client_assertion_type": {clientAssertionType},
			"client_assertion":      {makeClientAssertion(t, key, "client-signing-key", client.ID, "cc-jti-no-grant", time.Now())},
		})
		if response.Code != http.StatusBadRequest || !strings.Contains(response.Body.String(), "unauthorized_client") {
			t.Fatalf("grant-less client = %d %s", response.Code, response.Body.String())
		}
		server.Close()
	})

	t.Run("foreign resource is rejected", func(t *testing.T) {
		t.Parallel()
		client, key := clientCredentialsTestClient(t, []string{oauth.GrantClientCredentials})
		server := newTestServer(t)
		handler := newTestServerHandler(server)
		seedClientMetadataCache(server, &client)
		response := postClientCredentialsForm(t, handler, "/oauth/token", url.Values{
			"grant_type":            {oauth.GrantClientCredentials},
			"client_id":             {client.ID},
			"resource":              {"https://other.example/resource"},
			"client_assertion_type": {clientAssertionType},
			"client_assertion":      {makeClientAssertion(t, key, "client-signing-key", client.ID, "cc-jti-resource", time.Now())},
		})
		if response.Code != http.StatusBadRequest || !strings.Contains(response.Body.String(), "invalid_target") {
			t.Fatalf("foreign resource grant = %d %s", response.Code, response.Body.String())
		}
		server.Close()
	})

	t.Run("unsupported scope is rejected", func(t *testing.T) {
		t.Parallel()
		client, key := clientCredentialsTestClient(t, []string{oauth.GrantClientCredentials})
		server := newTestServer(t)
		handler := newTestServerHandler(server)
		seedClientMetadataCache(server, &client)
		response := postClientCredentialsForm(t, handler, "/oauth/token", url.Values{
			"grant_type":            {oauth.GrantClientCredentials},
			"client_id":             {client.ID},
			"scope":                 {"root"},
			"client_assertion_type": {clientAssertionType},
			"client_assertion":      {makeClientAssertion(t, key, "client-signing-key", client.ID, "cc-jti-scope", time.Now())},
		})
		if response.Code != http.StatusBadRequest || !strings.Contains(response.Body.String(), "invalid_scope") {
			t.Fatalf("unsupported scope grant = %d %s", response.Code, response.Body.String())
		}
		server.Close()
	})

	t.Run("revoked grant invalidates the token", func(t *testing.T) {
		t.Parallel()
		client, key := clientCredentialsTestClient(t, []string{oauth.GrantClientCredentials})
		server := newTestServer(t)
		handler := newTestServerHandler(server)
		seedClientMetadataCache(server, &client)
		now := time.Now()
		issued := postClientCredentialsForm(t, handler, "/oauth/token", url.Values{
			"grant_type":            {oauth.GrantClientCredentials},
			"client_id":             {client.ID},
			"scope":                 {"read"},
			"client_assertion_type": {clientAssertionType},
			"client_assertion":      {makeClientAssertion(t, key, "client-signing-key", client.ID, "cc-jti-revoke", now)},
		})
		if issued.Code != http.StatusOK {
			t.Fatalf("client_credentials grant = %d %s", issued.Code, issued.Body.String())
		}
		var token oauth.TokenResponse
		if err := json.NewDecoder(issued.Body).Decode(&token); err != nil {
			t.Fatalf("decode token response: %v", err)
		}
		revokeAssertion := makeClientAssertionClaims(t, key, "client-signing-key", &clientAssertionClaims{
			Issuer: client.ID, Subject: client.ID, Audience: testBaseURL + "/oauth/revoke", JWTID: "cc-jti-revoke-2",
			IssuedAt: now.Unix(), Expiry: now.Add(time.Minute).Unix(),
		})
		revoke := postClientCredentialsForm(t, handler, "/oauth/revoke", url.Values{
			"token":                 {token.AccessToken},
			"client_id":             {client.ID},
			"client_assertion_type": {clientAssertionType},
			"client_assertion":      {revokeAssertion},
		})
		if revoke.Code != http.StatusOK {
			t.Fatalf("revoke = %d %s", revoke.Code, revoke.Body.String())
		}
		if _, err := server.verifyBearer(newTestResourceRequest(t), token.AccessToken); err == nil {
			t.Fatal("revoked client-credentials token still verifies")
		}
		server.Close()
	})
}

func TestClientCredentialsRegistration(t *testing.T) {
	t.Parallel()

	t.Run("dynamic registration rejects client_credentials", func(t *testing.T) {
		t.Parallel()
		server := newTestServer(t)
		handler := newTestServerHandler(server)
		request := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/oauth/register", strings.NewReader(`{
			"client_name": "Machine Client",
			"redirect_uris": ["https://client.example/callback"],
			"token_endpoint_auth_method": "none",
			"grant_types": ["client_credentials"]
		}`))
		request.Header.Set("Content-Type", "application/json")
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, request)
		if response.Code != http.StatusBadRequest || !strings.Contains(response.Body.String(), "client_credentials requires private_key_jwt") {
			t.Fatalf("dynamic registration = %d %s", response.Code, response.Body.String())
		}
		server.Close()
	})

	t.Run("dynamic registration rejects confidential clients", func(t *testing.T) {
		t.Parallel()
		server := newTestServer(t)
		handler := newTestServerHandler(server)
		request := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/oauth/register", strings.NewReader(`{
			"client_name": "Machine Client",
			"redirect_uris": ["https://client.example/callback"],
			"token_endpoint_auth_method": "private_key_jwt",
			"grant_types": ["authorization_code"]
		}`))
		request.Header.Set("Content-Type", "application/json")
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, request)
		if response.Code != http.StatusBadRequest || !strings.Contains(response.Body.String(), "only public clients are supported") {
			t.Fatalf("confidential registration = %d %s", response.Code, response.Body.String())
		}
		server.Close()
	})

	t.Run("metadata document allows client_credentials without redirect URIs", func(t *testing.T) {
		t.Parallel()
		_, jwk := testDPoPRSAKeyPair(t)
		jwk.Kid = "m2m-key"
		jwk.Alg = oauth.JWTAlgRS256
		jwk.Use = "sig"
		clientID := "https://client.example/client.json"
		document, err := json.Marshal(oauth.ClientIDMetadataDocument{
			ClientID:                clientID,
			ClientName:              "Machine Client",
			TokenEndpointAuthMethod: oauth.TokenEndpointAuthPrivateKeyJWT,
			JWKS:                    &oauth.JWKSet{Keys: []oauth.JWK{*jwk}},
			GrantTypes:              []string{oauth.GrantClientCredentials},
		})
		if err != nil {
			t.Fatalf("marshal metadata: %v", err)
		}
		client, err := parseClientMetadata(clientID, document)
		if err != nil {
			t.Fatalf("parseClientMetadata: %v", err)
		}
		if len(client.RedirectURIs) != 0 || !clientSupportsGrant(&client, oauth.GrantClientCredentials) {
			t.Fatalf("parsed metadata client = %+v", client)
		}
	})

	t.Run("metadata document rejects public client_credentials", func(t *testing.T) {
		t.Parallel()
		document := `{
			"client_id": "https://client.example/client.json",
			"client_name": "Machine Client",
			"grant_types": ["client_credentials"]
		}`
		if _, err := parseClientMetadata("https://client.example/client.json", []byte(document)); err == nil {
			t.Fatal("public client_credentials metadata unexpectedly accepted")
		}
	})

	t.Run("metadata document rejects client_credentials with refresh_token", func(t *testing.T) {
		t.Parallel()
		_, jwk := testDPoPRSAKeyPair(t)
		jwk.Kid = "m2m-key"
		jwk.Alg = oauth.JWTAlgRS256
		jwk.Use = "sig"
		clientID := "https://client.example/client.json"
		document, err := json.Marshal(oauth.ClientIDMetadataDocument{
			ClientID:                clientID,
			ClientName:              "Machine Client",
			TokenEndpointAuthMethod: oauth.TokenEndpointAuthPrivateKeyJWT,
			JWKS:                    &oauth.JWKSet{Keys: []oauth.JWK{*jwk}},
			GrantTypes:              []string{oauth.GrantClientCredentials, oauth.GrantRefreshToken},
		})
		if err != nil {
			t.Fatalf("marshal metadata: %v", err)
		}
		if _, err := parseClientMetadata(clientID, document); err == nil {
			t.Fatal("client_credentials with refresh_token unexpectedly accepted")
		}
	})
}
