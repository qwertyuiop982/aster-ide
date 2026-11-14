package main

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"regexp"
	"time"
)

var certPattern = regexp.MustCompile(`^[0-9A-Fa-f]{64}$`)

func (a *app) appLinks(w http.ResponseWriter, r *http.Request) error {
	cert := os.Getenv("ASTER_ANDROID_CERT_SHA256")
	if !certPattern.MatchString(cert) {
		return fail(503, "CLIENT_NOT_PUBLISHED", "客户端签名信息尚未发布。")
	}
	colon := ""
	for i := 0; i < len(cert); i += 2 {
		if i > 0 {
			colon += ":"
		}
		colon += cert[i : i+2]
	}
	writeJSON(w, 200, []any{map[string]any{"relation": []string{"delegate_permission/common.handle_all_urls"}, "target": map[string]any{"namespace": "android_app", "package_name": "dev.aster.account", "sha256_cert_fingerprints": []string{colon}}}})
	return nil
}
func (a *app) authComplete(w http.ResponseWriter, r *http.Request) error {
	code := r.URL.Query().Get("code")
	if !pkcePattern.MatchString(code) {
		return fail(400, "INVALID_EXCHANGE", "登录链接无效，请从客户端重新登录。")
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Referrer-Policy", "no-referrer")
	_, e := fmt.Fprintf(w, `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>返回 Aster</title><h1>返回 Aster 完成登录</h1><p>此链接仅用于本次登录，90 秒内有效。</p><p><a href="asteraccount://login?code=%s">打开 Aster 客户端</a></p><p>登录令牌不会放入网页地址。</p></html>`, code)
	return e
}
func (a *app) maintenance(ctx context.Context) {
	ticker := time.NewTicker(time.Hour)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			for _, query := range []string{`DELETE FROM sessions WHERE expires_at<clock_timestamp()`, `DELETE FROM challenges WHERE expires_at<clock_timestamp()-interval '1 day'`, `DELETE FROM oauth_transactions WHERE expires_at<clock_timestamp()-interval '1 day'`, `DELETE FROM auth_limits WHERE started_at<clock_timestamp()-interval '2 days'`, `UPDATE usage_requests SET result_json=NULL WHERE status='completed' AND result_json IS NOT NULL AND updated_at<clock_timestamp()-interval '1 day'`, `UPDATE usage_requests SET status='pending_reconciliation',updated_at=clock_timestamp() WHERE status='running' AND updated_at<clock_timestamp()-interval '10 minutes'`} {
				work, cancel := context.WithTimeout(ctx, 10*time.Second)
				_, _ = a.db.ExecContext(work, query)
				cancel()
			}
		}
	}
}
