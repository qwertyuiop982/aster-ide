package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
)

// Replays must not incur another Chat usage probe. reserve rechecks the same
// fingerprint under the wallet lock to protect concurrent requests.
func (a *app) walletPreflight(ctx context.Context, id int64, requestID, fingerprint string, minimum int64) (json.RawMessage, error) {
	var previous, status string
	var raw []byte
	err := a.db.QueryRowContext(ctx, `SELECT fingerprint,status,result_json FROM usage_requests WHERE user_id=$1 AND request_id=$2`, id, requestID).Scan(&previous, &status, &raw)
	if err == nil {
		if previous != fingerprint {
			return nil, fail(409, "REQUEST_ID_CONFLICT", "相同请求编号不能用于不同内容或档位。")
		}
		if status == "completed" && len(raw) > 0 {
			return json.RawMessage(raw), nil
		}
		return nil, fail(409, "REQUEST_ALREADY_ACCEPTED", "请求已受理，请勿重复提交。")
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return nil, err
	}
	var available int64
	if err = a.db.QueryRowContext(ctx, `SELECT balance_units-reserved_units FROM users WHERE id=$1`, id).Scan(&available); err != nil {
		return nil, err
	}
	if available < minimum {
		return nil, fail(403, "INSUFFICIENT_CREDITS", "积分不足，请签到后重试。")
	}
	return nil, nil
}
