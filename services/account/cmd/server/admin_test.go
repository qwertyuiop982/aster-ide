package main

import (
	"context"
	"encoding/hex"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

func adminTestConfig() config {
	salt := []byte("test-only-salt-16")
	return config{secret: strings.Repeat("s", 48), publicURL: "https://asteride.cn", adminUsername: "admin", adminPasswordHash: "pbkdf2-sha256$100000$" + hex.EncodeToString(salt) + "$" + hex.EncodeToString(adminDerive("unit-test-only", salt, 100000)), adminLinkHash: digest(strings.Repeat("k", 43))}
}
func adminRequest(a *app, method, path, body string, cookie *http.Cookie, csrf bool) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, path, strings.NewReader(body))
	r.Header.Set("Origin", a.cfg.publicURL)
	r.Header.Set("Content-Type", "application/json")
	r.Header.Set("X-Aster-Admin-Token", strings.Repeat("k", 43))
	if cookie != nil {
		r.AddCookie(cookie)
		if csrf {
			r.Header.Set("X-Aster-CSRF", a.adminCSRF(cookie.Value))
		}
	}
	w := httptest.NewRecorder()
	a.routes().ServeHTTP(w, r)
	return w
}
func TestAdminPrecisionAndPassword(t *testing.T) {
	got := hex.EncodeToString(adminDerive("password", []byte("salt"), 1))
	if got != "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b" {
		t.Fatal("PBKDF2 known vector", got)
	}
	cfg := adminTestConfig()
	if !verifyAdminPassword(cfg.adminPasswordHash, "unit-test-only") || verifyAdminPassword(cfg.adminPasswordHash, "incorrect") {
		t.Fatal("password verification")
	}
	if e := validateAdminConfig(cfg); e != nil {
		t.Fatal(e)
	}
	cfg.adminLinkHash = "bad"
	if validateAdminConfig(cfg) == nil {
		t.Fatal("partial config accepted")
	}
	for raw, want := range map[string]int64{"0.001": 1, "25.125": 25125, "-1.001": -1001, "+100": 100000, "1000000": 1000000000} {
		got, e := parseAdminAmount(raw)
		if e != nil || got != want {
			t.Fatal(raw, got, e)
		}
	}
	for _, raw := range []string{"0", "-0.000", "1e3", "01", "1.0001", "1000000.001", "NaN", "1;DROP TABLE users", " 1", "999999999999999999"} {
		if _, e := parseAdminAmount(raw); e == nil {
			t.Fatal("invalid amount accepted", raw)
		}
	}
	if signedPoints(-1123) != "-1.123" || adminTotal("12345678901234567890") != "12345678901234567.890" {
		t.Fatal("precision lost")
	}
}
func TestAdminAuthenticationGuards(t *testing.T) {
	a := &app{cfg: adminTestConfig()}
	for _, path := range []string{"session", "summary", "users", "adjustments", "users/1"} {
		w := adminRequest(a, "GET", adminRoot+path, "", nil, false)
		if w.Code != 401 {
			t.Fatal(path, w.Code)
		}
	}
	for _, origin := range []string{"", "https://attacker.invalid"} {
		r := httptest.NewRequest("POST", adminRoot+"login", strings.NewReader(`{}`))
		r.Header.Set("Origin", origin)
		r.Header.Set("Content-Type", "application/json")
		w := httptest.NewRecorder()
		a.routes().ServeHTTP(w, r)
		if w.Code != 403 {
			t.Fatal("origin", w.Code)
		}
	}
	r := httptest.NewRequest("POST", adminRoot+"login", strings.NewReader(`{}`))
	r.Header.Set("Origin", a.cfg.publicURL)
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	a.routes().ServeHTTP(w, r)
	if w.Code != 401 {
		t.Fatal("entry token guard", w.Code)
	}
	cookie := &http.Cookie{Name: adminCookie, Value: strings.Repeat("q", 43)}
	if w = adminRequest(a, "POST", adminRoot+"users/1/adjustments", `{}`, cookie, false); w.Code != 403 {
		t.Fatal("CSRF guard", w.Code)
	}
	a.cfg = config{}
	if w = adminRequest(a, "GET", adminRoot+"summary", "", nil, false); w.Code != 404 {
		t.Fatal("disabled admin", w.Code)
	}
}
func TestAdminLoginAndTransactionalCredits(t *testing.T) {
	db := testDB(t)
	a := &app{db: db, cfg: adminTestConfig()}
	tx, e := db.Begin()
	if e != nil {
		t.Fatal(e)
	}
	uid, e := identityAccount(context.Background(), tx, "email", "admin-credit-test@example.invalid", "Credit test account")
	if e != nil {
		t.Fatal(e)
	}
	if e = tx.Commit(); e != nil {
		t.Fatal(e)
	}
	bad := adminRequest(a, "POST", adminRoot+"login", `{"username":"admin","password":"wrong"}`, nil, false)
	if bad.Code != 401 {
		t.Fatal("wrong credentials", bad.Code)
	}
	logged := adminRequest(a, "POST", adminRoot+"login", `{"username":"admin","password":"unit-test-only"}`, nil, false)
	if logged.Code != 200 {
		t.Fatal(logged.Code, logged.Body.String())
	}
	cookies := logged.Result().Cookies()
	if len(cookies) != 1 {
		t.Fatal("missing session cookie")
	}
	cookie := cookies[0]
	if !cookie.Secure || !cookie.HttpOnly || cookie.SameSite != http.SameSiteStrictMode || cookie.Path != adminRoot {
		t.Fatal("cookie attributes")
	}
	if strings.Contains(logged.Body.String(), cookie.Value) {
		t.Fatal("session leaked into body")
	}
	for _, path := range []string{"session", "summary", "users?q=Credit", "adjustments", fmt.Sprintf("users/%d", uid)} {
		w := adminRequest(a, "GET", adminRoot+path, "", cookie, false)
		if w.Code != 200 {
			t.Fatal(path, w.Code, w.Body.String())
		}
	}
	path := fmt.Sprintf("%susers/%d/adjustments", adminRoot, uid)
	body := `{"request_id":"duplicate-credit-request-001","points":"25.125","reason":"测试活动奖励"}`
	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			w := adminRequest(a, "POST", path, body, cookie, true)
			if w.Code != 200 {
				t.Error(w.Code, w.Body.String())
			}
		}()
	}
	wg.Wait()
	var balance, reserved, rows int64
	if e = db.QueryRow(`SELECT balance_units,reserved_units FROM users WHERE id=$1`, uid).Scan(&balance, &reserved); e != nil {
		t.Fatal(e)
	}
	if balance != 1025125 || reserved != 0 {
		t.Fatal("duplicate credit", balance, reserved)
	}
	if e = db.QueryRow(`SELECT count(*) FROM ledger WHERE user_id=$1 AND kind='admin_adjustment'`, uid).Scan(&rows); e != nil || rows != 1 {
		t.Fatal("duplicate ledger", rows, e)
	}
	conflict := strings.Replace(body, "25.125", "26.125", 1)
	if w := adminRequest(a, "POST", path, conflict, cookie, true); w.Code != 409 {
		t.Fatal("conflicting request accepted", w.Code)
	}
	if w := adminRequest(a, "POST", path, body, cookie, false); w.Code != 403 {
		t.Fatal("missing CSRF accepted")
	}
	// Compete with the same wallet row lock used by live model reservations.
	for i := 0; i < 10; i++ {
		wg.Add(2)
		go func(i int) {
			defer wg.Done()
			_, e := a.reserve(context.Background(), uid, fmt.Sprintf("parallel-reserve-%d", i), "admin-test", 128)
			if e != nil {
				t.Error(e)
			}
		}(i)
		go func(i int) {
			defer wg.Done()
			b := fmt.Sprintf(`{"request_id":"parallel-credit-%03d","points":"0.001","reason":"并发精度验证"}`, i)
			w := adminRequest(a, "POST", path, b, cookie, true)
			if w.Code != 200 {
				t.Error(w.Code, w.Body.String())
			}
		}(i)
	}
	wg.Wait()
	if e = db.QueryRow(`SELECT balance_units,reserved_units FROM users WHERE id=$1`, uid).Scan(&balance, &reserved); e != nil {
		t.Fatal(e)
	}
	if balance != 1025135 || reserved != 1280 {
		t.Fatal("concurrent wallet lock", balance, reserved)
	}
	over := `{"request_id":"reserved-debit-fail-001","points":"-1023.856","reason":"不能扣除预占"}`
	if w := adminRequest(a, "POST", path, over, cookie, true); w.Code != 409 || !strings.Contains(w.Body.String(), "RESERVED_CREDITS") {
		t.Fatal("reservation not protected", w.Code, w.Body.String())
	}
	debit := `{"request_id":"valid-debit-request-001","points":"-1.001","reason":"账单精度修正"}`
	if w := adminRequest(a, "POST", path, debit, cookie, true); w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	if e = db.QueryRow(`SELECT balance_units,reserved_units FROM users WHERE id=$1`, uid).Scan(&balance, &reserved); e != nil || balance != 1024134 || reserved != 1280 {
		t.Fatal(balance, reserved, e)
	}
	detail := adminRequest(a, "GET", fmt.Sprintf("%susers/%d", adminRoot, uid), "", cookie, false)
	if !strings.Contains(detail.Body.String(), "账单精度修正") || !strings.Contains(detail.Body.String(), `"points":"-1.001"`) {
		t.Fatal("missing audit", detail.Body.String())
	}
	old := a.cfg.adminLinkHash
	a.cfg.adminLinkHash = digest("rotated-link")
	if w := adminRequest(a, "GET", adminRoot+"session", "", cookie, false); w.Code != 401 {
		t.Fatal("credential rotation did not invalidate session")
	}
	a.cfg.adminLinkHash = old
	if w := adminRequest(a, "POST", adminRoot+"logout", `{}`, cookie, true); w.Code != 200 {
		t.Fatal("logout", w.Code)
	}
	if w := adminRequest(a, "GET", adminRoot+"session", "", cookie, false); w.Code != 401 {
		t.Fatal("revoked session accepted")
	}
}
