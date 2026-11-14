package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"time"

	"aster.local/service/internal/sub2billing"
)

func points(units int64) string { return fmt.Sprintf("%d.%03d", units/1000, units%1000) }
func dayInfo(now time.Time) (string, time.Time) {
	local := now.In(beijing)
	return local.Format("2006-01-02"), time.Date(local.Year(), local.Month(), local.Day()+1, 0, 0, 0, 0, beijing)
}
func (a *app) wallet(w http.ResponseWriter, r *http.Request, id int64) error {
	var balance, reserved int64
	var now time.Time
	var checked bool
	e := a.db.QueryRowContext(r.Context(), `WITH stamp AS (SELECT clock_timestamp() AS now) SELECT u.balance_units,u.reserved_units,stamp.now,EXISTS(SELECT 1 FROM checkins c WHERE c.user_id=u.id AND c.beijing_date=(stamp.now AT TIME ZONE 'Asia/Shanghai')::date) FROM users u CROSS JOIN stamp WHERE u.id=$1`, id).Scan(&balance, &reserved, &now, &checked)
	if e != nil {
		return e
	}
	day, next := dayInfo(now)
	writeJSON(w, 200, map[string]any{"balance": points(balance), "available": points(balance - reserved), "reserved": points(reserved), "timezone": "Asia/Shanghai", "timezone_label": "北京时间", "beijing_date": day, "server_time": now.UTC().Format(time.RFC3339), "next_reset_at": next.Format(time.RFC3339), "checked_in": checked, "daily_reward": 100, "points_per_1000_tokens": map[string]int{"Aster-free": freeUnitsPerToken, "Aster-free go": goUnitsPerToken}})
	return nil
}
func (a *app) checkin(w http.ResponseWriter, r *http.Request, id int64) error {
	var input struct{}
	if e := decode(w, r, &input); e != nil {
		return e
	}
	tx, e := a.db.BeginTx(r.Context(), nil)
	if e != nil {
		return e
	}
	defer tx.Rollback()
	var balance int64
	if e = tx.QueryRowContext(r.Context(), `SELECT balance_units FROM users WHERE id=$1 FOR UPDATE`, id).Scan(&balance); e != nil {
		return e
	}
	var now time.Time
	if e = tx.QueryRowContext(r.Context(), `SELECT clock_timestamp()`).Scan(&now); e != nil {
		return e
	}
	day, _ := dayInfo(now)
	result, e := tx.ExecContext(r.Context(), `INSERT INTO checkins(user_id,beijing_date,created_at) VALUES($1,$2,$3) ON CONFLICT DO NOTHING`, id, day, now)
	if e != nil {
		return e
	}
	n, e := result.RowsAffected()
	if e != nil {
		return e
	}
	if n == 1 {
		if _, e = tx.ExecContext(r.Context(), `UPDATE users SET balance_units=balance_units+100000 WHERE id=$1`, id); e != nil {
			return e
		}
		if _, e = tx.ExecContext(r.Context(), `INSERT INTO ledger(user_id,kind,reference,delta_units) VALUES($1,'checkin',$2,100000)`, id, day); e != nil {
			return e
		}
		balance += 100000
	}
	if e = tx.Commit(); e != nil {
		return e
	}
	writeJSON(w, 200, map[string]any{"reward": n * 100, "already_checked_in": n == 0, "balance": points(balance), "beijing_date": day, "timezone": "Asia/Shanghai", "timezone_label": "北京时间"})
	return nil
}
func (a *app) history(w http.ResponseWriter, r *http.Request, id int64) error {
	rows, e := a.db.QueryContext(r.Context(), `SELECT kind,reference,delta_units,created_at FROM ledger WHERE user_id=$1 ORDER BY id DESC LIMIT 100`, id)
	if e != nil {
		return e
	}
	defer rows.Close()
	items := make([]any, 0)
	for rows.Next() {
		var kind, ref string
		var delta int64
		var at time.Time
		if e = rows.Scan(&kind, &ref, &delta, &at); e != nil {
			return e
		}
		sign := ""
		value := delta
		if value < 0 {
			sign = "-"
			value = -value
		}
		items = append(items, map[string]any{"kind": kind, "reference": ref, "points": sign + points(value), "created_at": at.In(beijing).Format(time.RFC3339)})
	}
	if e = rows.Err(); e != nil {
		return e
	}
	writeJSON(w, 200, map[string]any{"items": items, "timezone": "Asia/Shanghai"})
	return nil
}
func (a *app) reserve(ctx context.Context, id int64, requestID, fingerprint string, units int64) (json.RawMessage, error) {
	if units <= 0 || units > 128000*goUnitsPerToken {
		return nil, fail(400, "INVALID_BUDGET", "请求预算无效。")
	}
	tx, e := a.db.BeginTx(ctx, nil)
	if e != nil {
		return nil, e
	}
	defer tx.Rollback()
	var balance, reserved int64
	if e = tx.QueryRowContext(ctx, `SELECT balance_units,reserved_units FROM users WHERE id=$1 FOR UPDATE`, id).Scan(&balance, &reserved); e != nil {
		return nil, e
	}
	var oldFingerprint, status string
	var raw []byte
	e = tx.QueryRowContext(ctx, `SELECT fingerprint,status,result_json FROM usage_requests WHERE user_id=$1 AND request_id=$2`, id, requestID).Scan(&oldFingerprint, &status, &raw)
	if e == nil {
		if oldFingerprint != fingerprint {
			return nil, fail(409, "REQUEST_ID_CONFLICT", "相同请求编号不能用于不同内容。")
		}
		if status == "completed" && len(raw) > 0 {
			return json.RawMessage(raw), nil
		}
		return nil, fail(409, "REQUEST_ALREADY_ACCEPTED", "请求已受理，请勿重复提交。")
	}
	if !errors.Is(e, sql.ErrNoRows) {
		return nil, e
	}
	if balance-reserved < units {
		return nil, fail(403, "INSUFFICIENT_CREDITS", "积分不足，请签到后重试。")
	}
	if _, e = tx.ExecContext(ctx, `UPDATE users SET reserved_units=reserved_units+$1 WHERE id=$2`, units, id); e != nil {
		return nil, e
	}
	if _, e = tx.ExecContext(ctx, `INSERT INTO usage_requests(user_id,request_id,fingerprint,status,reserved_units,prompt_version) VALUES($1,$2,$3,'running',$4,$5)`, id, requestID, fingerprint, units, promptVersion); e != nil {
		return nil, e
	}
	return nil, tx.Commit()
}

// settle uses the selected sub2api backend command/fingerprint, with integer token units as costs.
// Aster owns the single wallet; the original currency gateway is not deployed or reachable.
func (a *app) settle(ctx context.Context, id int64, requestID string, input, output int64, result any, known bool) error {
	return a.settleTier(ctx, id, requestID, input, output, result, known, a.tier(false))
}
func (a *app) settleTier(ctx context.Context, id int64, requestID string, input, output int64, result any, known bool, tier freeTier) error {
	tx, e := a.db.BeginTx(ctx, nil)
	if e != nil {
		return e
	}
	defer tx.Rollback()
	var balance, allReserved int64
	if e = tx.QueryRowContext(ctx, `SELECT balance_units,reserved_units FROM users WHERE id=$1 FOR UPDATE`, id).Scan(&balance, &allReserved); e != nil {
		return e
	}
	var held int64
	var status string
	if e = tx.QueryRowContext(ctx, `SELECT reserved_units,status FROM usage_requests WHERE user_id=$1 AND request_id=$2 FOR UPDATE`, id, requestID).Scan(&held, &status); e != nil {
		return e
	}
	if status != "running" {
		return nil
	}
	if !known || input < 0 || output < 0 || tier.unitsPerToken < 1 || ((input+output)*tier.unitsPerToken > held && !tier.chatCount) {
		_, e = tx.ExecContext(ctx, `UPDATE usage_requests SET status='pending_reconciliation',updated_at=clock_timestamp() WHERE user_id=$1 AND request_id=$2`, id, requestID)
		if e != nil {
			return e
		}
		return tx.Commit()
	}
	// The user row is locked. A drifted formal charge can consume additional
	// available credit, but never another request's reservation or a negative
	// balance. If insufficient, release this hold and charge nothing.
	actual := (input + output) * tier.unitsPerToken
	if actual > held && tier.chatCount {
		if input > 128000 || output > 2048 || input+output > 128000 {
			return fail(502, "USAGE_RECONCILIATION_REQUIRED", "模型返回的用量超过上下文上限。")
		}
		if balance-allReserved < actual-held {
			if _, e = tx.ExecContext(ctx, `UPDATE users SET reserved_units=reserved_units-$1 WHERE id=$2`, held, id); e != nil {
				return e
			}
			if _, e = tx.ExecContext(ctx, `UPDATE usage_requests SET status='failed',input_tokens=$1,output_tokens=$2,charged_units=0,updated_at=clock_timestamp() WHERE user_id=$3 AND request_id=$4`, input, output, id, requestID); e != nil {
				return e
			}
			if e = tx.Commit(); e != nil {
				return e
			}
			return fail(403, "INSUFFICIENT_CREDITS", "正式请求的实际用量超过可用积分，本次不扣积分。")
		}
	}
	command := sub2billing.UsageBillingCommand{RequestID: requestID, UserID: id, Model: tier.name, InputTokens: int(input), OutputTokens: int(output), BalanceCost: float64((input + output) * tier.unitsPerToken)}
	command.Normalize()
	charged := (input + output) * tier.unitsPerToken
	var raw []byte
	finalStatus := "failed"
	if result != nil {
		raw, e = json.Marshal(result)
		if e != nil {
			return e
		}
		finalStatus = "completed"
	}
	if _, e = tx.ExecContext(ctx, `UPDATE users SET reserved_units=reserved_units-$1,balance_units=balance_units-$2 WHERE id=$3`, held, charged, id); e != nil {
		return e
	}
	if charged > 0 {
		if _, e = tx.ExecContext(ctx, `INSERT INTO ledger(user_id,kind,reference,delta_units) VALUES($1,'usage',$2,$3)`, id, requestID, -charged); e != nil {
			return e
		}
	}
	if _, e = tx.ExecContext(ctx, `UPDATE usage_requests SET status=$1,input_tokens=$2,output_tokens=$3,charged_units=$4,result_json=$5,billing_fingerprint=$6,updated_at=clock_timestamp() WHERE user_id=$7 AND request_id=$8`, finalStatus, input, output, charged, nullableJSON(raw), command.RequestFingerprint, id, requestID); e != nil {
		return e
	}
	return tx.Commit()
}
func nullableJSON(raw []byte) any {
	if len(raw) == 0 {
		return nil
	}
	return string(raw)
}
