package main

import (
	"encoding/json"
	"net/http/httptest"
	"testing"
)

func TestPublicReleaseAndTierRates(t *testing.T) {
	a := &app{db: testDB(t)}
	health := httptest.NewRecorder()
	a.routes().ServeHTTP(health, httptest.NewRequest("GET", "/api/v1/health", nil))
	var status struct {
		Version string `json:"version"`
	}
	if err := json.Unmarshal(health.Body.Bytes(), &status); err != nil || status.Version != "v1.06-aster" {
		t.Fatalf("unexpected public version: %s", health.Body.String())
	}
	models := httptest.NewRecorder()
	a.routes().ServeHTTP(models, httptest.NewRequest("GET", "/api/v1/models", nil))
	var listing struct {
		Models []struct {
			ID   string `json:"id"`
			Rate int64  `json:"points_per_1000_tokens"`
		} `json:"models"`
	}
	if err := json.Unmarshal(models.Body.Bytes(), &listing); err != nil || len(listing.Models) != 2 {
		t.Fatalf("invalid model listing: %s", models.Body.String())
	}
	for i, rate := range []int64{1, 5} {
		if listing.Models[i].Rate != rate || a.tier(i == 1).unitsPerToken != rate {
			t.Fatalf("advertised and settled rates differ for %s", listing.Models[i].ID)
		}
	}
}
