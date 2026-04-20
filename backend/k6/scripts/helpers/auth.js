import http from 'k6/http';

const KEYCLOAK_URL = __ENV.KEYCLOAK_URL || 'http://localhost:8180';
const REALM = 'robomart';
// client_id must match realm config: robo-mart-frontend (with hyphen)
const CLIENT_ID = 'robo-mart-frontend';

/**
 * Authenticate a user and return JWT access token.
 * @param {string} username
 * @param {string} password
 * @returns {string|null} JWT access token or null on failure
 */
export function getToken(username, password) {
    const payload = {
        grant_type: 'password',
        client_id: CLIENT_ID,
        username: username,
        password: password,
    };

    const res = http.post(
        `${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token`,
        payload,
        { tags: { name: 'auth/token' } }
    );

    if (res.status !== 200) {
        console.error(`Auth failed for ${username}: ${res.status} ${res.body}`);
        return null;
    }

    return JSON.parse(res.body).access_token;
}
