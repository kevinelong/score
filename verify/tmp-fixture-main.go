// Command tmp-fixture seeds the local reference database using the server's own
// internal/seed package (the same fixtures its acceptance tests use), prints what a
// client can expect for the live tournament, and mints a director session for
// exercising the authenticated paths.
//
// This is a throwaway development helper for the merged Android app's verification:
// it lives untracked inside a scratch clone of 15ball-scoresheet and is never
// committed. Run it with DATABASE_PATH pointing at the same file the server uses:
//
//	DATABASE_PATH=C:/Users/kevin/score-ref/run/fifteenball.db \
//	  toolchain/go/bin/go.exe run ./tmp-fixture
package main

import (
	"context"
	"fmt"
	"log"

	"github.com/kevinelong/15ball-scoresheet/server/internal/auth"
	"github.com/kevinelong/15ball-scoresheet/server/internal/config"
	"github.com/kevinelong/15ball-scoresheet/server/internal/mail"
	"github.com/kevinelong/15ball-scoresheet/server/internal/seed"
	"github.com/kevinelong/15ball-scoresheet/server/internal/store"
)

func main() {
	cfg := config.Load()
	st, err := store.Open(cfg.DatabasePath)
	if err != nil {
		log.Fatalf("store: %v", err)
	}
	defer st.Close()

	ctx := context.Background()
	if err := seed.Seed(ctx, st.DB); err != nil {
		log.Fatalf("seed: %v", err)
	}
	fmt.Printf("seeded %s\n\n", cfg.DatabasePath)

	fmt.Printf("tournaments (id is also the slug):\n")
	rows, err := st.DB.QueryContext(ctx,
		`SELECT id, name, state, visibility FROM tournaments ORDER BY id`)
	if err != nil {
		log.Fatalf("tournaments: %v", err)
	}
	for rows.Next() {
		var id, name, state, vis string
		if err := rows.Scan(&id, &name, &state, &vis); err != nil {
			log.Fatalf("scan: %v", err)
		}
		fmt.Printf("  %-12s %-14s %-18s %s\n", id, name, state, vis)
	}
	rows.Close()

	fmt.Printf("\n%s entrants:\n", seed.TournamentLive)
	rows, err = st.DB.QueryContext(ctx,
		`SELECT id, display_name, state FROM entrants WHERE tournament_id=? ORDER BY id`,
		seed.TournamentLive)
	if err != nil {
		log.Fatalf("entrants: %v", err)
	}
	for rows.Next() {
		var id, name, state string
		if err := rows.Scan(&id, &name, &state); err != nil {
			log.Fatalf("scan: %v", err)
		}
		fmt.Printf("  %-18s %-10s %s\n", id, name, state)
	}
	rows.Close()

	fmt.Printf("\n%s matches:\n", seed.TournamentLive)
	rows, err = st.DB.QueryContext(ctx, `
		SELECT m.id, m.slot, m.state, COALESCE(ea.display_name, '-'), COALESCE(eb.display_name, '-'),
		       COALESCE((SELECT r.winner_entrant_id FROM match_results r
		                  WHERE r.match_id = m.id AND r.superseded_by IS NULL
		                  ORDER BY r.result_version DESC LIMIT 1), '')
		FROM matches m
		LEFT JOIN entrants ea ON ea.id = m.entrant_a_id
		LEFT JOIN entrants eb ON eb.id = m.entrant_b_id
		WHERE m.tournament_id = ?
		ORDER BY m.slot`, seed.TournamentLive)
	if err != nil {
		log.Fatalf("matches: %v", err)
	}
	for rows.Next() {
		var id, state string
		var slot int
		var a, b, winner string
		if err := rows.Scan(&id, &slot, &state, &a, &b, &winner); err != nil {
			log.Fatalf("scan: %v", err)
		}
		fmt.Printf("  %-18s slot=%d %-11s %-9s vs %-9s winner=%q\n", id, slot, state, a, b, winner)
	}
	rows.Close()

	a := auth.New(st.DB, cfg, &mail.LogMailer{})
	raw, err := a.CreateSession(ctx, seed.UserDirector, "", "")
	if err != nil {
		log.Fatalf("session: %v", err)
	}
	fmt.Printf("\nseeded director (%s) session cookie:\nfifteenball_session=%s\n", seed.UserDirector, raw)

	if err := seed141(ctx, st.DB); err != nil {
		log.Fatalf("14.1 fixture: %v", err)
	}
	fmt.Printf("\n14.1 fixture: %s straight_14_1 in_progress on table 1 (Iris vs Jonah)\n", Race141)

	if err := seedRace(ctx, st.DB); err != nil {
		log.Fatalf("race fixture: %v", err)
	}
	fmt.Printf("\nrace fixture:\n")
	rows, err = st.DB.QueryContext(ctx,
		`SELECT id, name, game, state, visibility FROM tournaments WHERE id = ?`, RaceTournament)
	if err != nil {
		log.Fatalf("race tournament: %v", err)
	}
	for rows.Next() {
		var id, name, game, state, vis string
		if err := rows.Scan(&id, &name, &game, &state, &vis); err != nil {
			log.Fatalf("scan: %v", err)
		}
		fmt.Printf("  %-12s %-22s game=%-20s %-12s %s\n", id, name, game, state, vis)
	}
	rows.Close()
	rows, err = st.DB.QueryContext(ctx, `
		SELECT m.id, m.state, COALESCE(ea.display_name, '-'), COALESCE(eb.display_name, '-')
		FROM matches m
		LEFT JOIN entrants ea ON ea.id = m.entrant_a_id
		LEFT JOIN entrants eb ON eb.id = m.entrant_b_id
		WHERE m.tournament_id = ? ORDER BY m.slot`, RaceTournament)
	if err != nil {
		log.Fatalf("race matches: %v", err)
	}
	for rows.Next() {
		var id, state, a, b string
		if err := rows.Scan(&id, &state, &a, &b); err != nil {
			log.Fatalf("scan: %v", err)
		}
		fmt.Printf("  %-18s %-12s %s vs %s\n", id, state, a, b)
	}
	rows.Close()
}

// RaceTournament is a second discipline, so the app can be shown reading the game from the server
// instead of assuming 15-ball rotation.
//
// The game value is '9ball' because that is what their API accepts: tournaments.go validates `game`
// on create against 15ball_rotation, 8ball, 9ball, 10ball, straight_14_1, bank_pool, one_pocket - and
// `game` is immutable afterwards. There is nowhere on the server to put "race to 11", which is the
// whole point of this fixture: the target has to travel in the opaque result payload.
const RaceTournament = "t_race_001"

// seedRace writes a 9-ball race-to-11 tournament: four entrants and two matches, both in progress
// at their own table, so the app has a real choice of match to score. Idempotent.
func seedRace(ctx context.Context, ex seed.Execer) error {
	const base = 1767225600
	if _, err := ex.ExecContext(ctx,
		`INSERT OR IGNORE INTO tournaments (id,slug,name,game,state,visibility,created_by,created_at,updated_at,version)
		 VALUES (?,?,?, '9ball', 'in_progress', 'public', ?, ?, ?, 1)`,
		RaceTournament, RaceTournament, "Thursday 9-Ball (race to 11)", seed.UserDirector, base, base); err != nil {
		return err
	}
	if _, err := ex.ExecContext(ctx,
		`INSERT OR IGNORE INTO divisions (id,tournament_id,name,format,state,created_at,updated_at)
		 VALUES (?,?, 'Open', 'single_elimination', 'active', ?, ?)`,
		"d_"+RaceTournament, RaceTournament, base, base); err != nil {
		return err
	}
	names := []string{"Avery", "Blake", "Casey", "Drew"}
	for i, n := range names {
		if _, err := ex.ExecContext(ctx,
			`INSERT OR IGNORE INTO entrants (id,tournament_id,division_id,display_name,state,check_in_at,created_at,updated_at,version)
			 VALUES (?,?,?,?, 'checked_in', ?, ?, ?, 1)`,
			fmt.Sprintf("e_%s_%d", RaceTournament, i+1), RaceTournament, "d_"+RaceTournament, n, base, base, base); err != nil {
			return err
		}
	}
	specs := []struct {
		slot  int
		state string
		table string
	}{{0, "in_progress", "4"}, {1, "in_progress", "5"}}
	for _, s := range specs {
		var scorer, started interface{}
		if s.state == "in_progress" {
			scorer = seed.UserScorer1
			started = base
		}
		if _, err := ex.ExecContext(ctx,
			`INSERT OR IGNORE INTO matches (id,tournament_id,division_id,bracket_round,slot,entrant_a_id,entrant_b_id,state,assigned_scorekeeper_user_id,table_ref,started_at,completed_at,version,created_at,updated_at)
			 VALUES (?,?,?,1,?,?,?,?,?,?,?,NULL,1,?,?)`,
			fmt.Sprintf("m_%s_%d", RaceTournament, s.slot), RaceTournament, "d_"+RaceTournament, s.slot,
			fmt.Sprintf("e_%s_%d", RaceTournament, s.slot*2+1), fmt.Sprintf("e_%s_%d", RaceTournament, s.slot*2+2),
			s.state, scorer, s.table, started, base, base); err != nil {
			return err
		}
	}
	return nil
}

// Race141 is the points game: 14.1 is played to a three-digit target, which is why the app carries
// three digits at all.
const Race141 = "t_141_001"

// seed141 writes a straight-pool tournament already in progress on table 1. Idempotent.
func seed141(ctx context.Context, ex seed.Execer) error {
	const base = 1767225600
	if _, err := ex.ExecContext(ctx,
		`INSERT OR IGNORE INTO tournaments (id,slug,name,game,state,visibility,created_by,created_at,updated_at,version)
		 VALUES (?,?,?, 'straight_14_1', 'in_progress', 'public', ?, ?, ?, 1)`,
		Race141, Race141, "Sunday 14.1 (to 100)", seed.UserDirector, base, base); err != nil {
		return err
	}
	if _, err := ex.ExecContext(ctx,
		`INSERT OR IGNORE INTO divisions (id,tournament_id,name,format,state,created_at,updated_at)
		 VALUES (?,?, 'Open', 'single_elimination', 'active', ?, ?)`,
		"d_"+Race141, Race141, base, base); err != nil {
		return err
	}
	for i, n := range []string{"Iris", "Jonah"} {
		if _, err := ex.ExecContext(ctx,
			`INSERT OR IGNORE INTO entrants (id,tournament_id,division_id,display_name,state,check_in_at,created_at,updated_at,version)
			 VALUES (?,?,?,?, 'checked_in', ?, ?, ?, 1)`,
			fmt.Sprintf("e_%s_%d", Race141, i+1), Race141, "d_"+Race141, n, base, base, base); err != nil {
			return err
		}
	}
	if _, err := ex.ExecContext(ctx,
		`INSERT OR IGNORE INTO matches (id,tournament_id,division_id,bracket_round,slot,entrant_a_id,entrant_b_id,state,assigned_scorekeeper_user_id,table_ref,started_at,completed_at,version,created_at,updated_at)
		 VALUES (?,?,?,1,0,?,?, 'in_progress', ?, '1', ?, NULL, 1, ?, ?)`,
		"m_"+Race141+"_0", Race141, "d_"+Race141,
		"e_"+Race141+"_1", "e_"+Race141+"_2", seed.UserScorer1, base, base, base); err != nil {
		return err
	}
	return nil
}
