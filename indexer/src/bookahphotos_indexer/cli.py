"""Command line: `bookahphotos-indexer run | fake-snapshot | status`. Logs hold counts and ids only, never file names or coordinates."""
from __future__ import annotations

import argparse
import logging
import sys
import threading
import time
from datetime import datetime
from pathlib import Path

from . import __version__, build, enrich, publish, schema
from .config import Config
from .creds import read_credential
from .dav import Dav, ListingError
from .fake import make_fake_snapshot
from .places import DEFAULT_FILE, PlaceIndex
from .scan import SafeguardTripped, scan
from .snapshot import build_manifest
from .state import State

MODELS = {"search_model": "xlmr-vit-b32-laion5b (XLM-RoBERTa ViT-B/32, 512 dimensions)", "face_model": "yunet+sface (boxes and person ids only; descriptions stay on the PC)"}


def _text_encoder():
    from . import content
    return content.TextEncoder(None)           # the 120 label sentences are encoded once on the CPU (the result is cached in a small file)


def _setup_logging(state_dir: Path) -> None:
    logs = state_dir / "logs"
    logs.mkdir(parents=True, exist_ok=True)
    logging.basicConfig(filename=logs / f"indexer-{datetime.now():%Y-%m-%d}.log", level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s", encoding="utf-8")


def cmd_run(args: argparse.Namespace) -> int:
    cfg = Config.load(Path(args.config) if args.config else None)
    if args.roots:
        cfg.roots = args.roots
    if args.credential:
        cfg.credential_target = args.credential
    state_dir = Path(cfg.state_dir)
    _setup_logging(state_dir)
    log = logging.getLogger("bookahphotos.run")
    cfg.all_roots_checked()
    user, password = read_credential(cfg.credential_target)
    dav = Dav(cfg.base_url, cfg.user or user, password, cfg.request_timeout_s, cfg.min_request_interval_s)
    places = PlaceIndex.load() if DEFAULT_FILE.exists() else None
    if places is None:
        print("note: the GeoNames file is missing, so no places are added")
    state = State(state_dir / (args.state_name or "indexer.db"))
    rid = state.start_run()
    try:
        res = scan(dav, state, cfg, rid, accept_deletions=args.accept_deletions)
    except ListingError as e:
        state.finish_run(rid, "aborted", str(e))
        print(f"STOPPED: a listing failed ({e}). Nothing was treated as deleted and nothing was published. Run again later.")
        return 2
    except SafeguardTripped as e:
        state.finish_run(rid, "blocked", str(e))
        print(f"STOPPED by the deletion safeguard: {e}")
        return 3
    print(f"listed {res.folders_listed} folders, skipped {res.folders_skipped} unchanged; new {res.new}, changed {res.changed}, same {res.same}, removed {res.deleted}")
    log.info("scan folders=%d skipped=%d new=%d changed=%d same=%d deleted=%d", res.folders_listed, res.folders_skipped, res.new, res.changed, res.same, res.deleted)

    if args.reread_metadata:
        print(f"re-reading metadata for {state.reset_metadata()} files")
    elif state.kv_get("offset_zero_fixed") != "1":
        # one-time: files whose time was stored as UTC+0 only because the epoch equalled the wall clock (metadata version 4)
        print(f"re-reading metadata for {state.reset_offset_zero()} files stored as UTC+0")
        state.kv_set("offset_zero_fixed", "1")
    if state.kv_get("video_headers_done") != "1":
        # one-time: undated videos get their date from their own header (metadata version 5)
        print(f"re-reading {state.reset_undated_videos()} undated videos from their headers")
        state.kv_set("video_headers_done", "1")
    m = enrich.enrich_metadata(dav, state, cfg, places, args.max_metadata)
    print(f"metadata: {m.metadata_done} done ({m.metadata_fallback_head} from the file head, {m.metadata_no_exif} unknown to Memories, {m.metadata_undated} with no usable date), {m.metadata_failed} failed, {m.metadata_gave_up} gave up after 3 tries; {m.metadata_video_header} videos dated from their own header")
    waiting, gave_up = state.count_stuck()
    if waiting or gave_up:
        print(f"files still waiting for metadata: {waiting}; files that gave up earlier: {gave_up}")
    log.info("metadata done=%d head=%d no_exif=%d failed=%d", m.metadata_done, m.metadata_fallback_head, m.metadata_no_exif, m.metadata_failed)

    if not args.skip_previews:
        stop = threading.Event()
        timer = threading.Timer(args.minutes * 60, stop.set) if args.minutes else None
        if timer:
            timer.daemon = True
            timer.start()
        p = enrich.enrich_previews(dav, state, cfg, args.max_previews, stop)
        if timer:
            timer.cancel()
        print(f"placeholders: {p.previews_done} made, {p.previews_failed} failed" + (" (stopped at the time limit)" if stop.is_set() else ""))
        log.info("previews done=%d failed=%d stopped=%s", p.previews_done, p.previews_failed, stop.is_set())

    if getattr(args, "no_snapshot", False):
        state.finish_run(rid, "ok", "no snapshot requested")
        return 0
    digest = build.rows_digest(state)
    if digest == state.kv_get("published_digest") and not args.force_publish:
        print("nothing changed since the last snapshot; not publishing")
        state.finish_run(rid, "ok", "no change")
        return 0
    generation = int(state.kv_get("generation", "0")) + 1
    tmp = state_dir / "tmp"
    tmp.mkdir(parents=True, exist_ok=True)
    snap = build.build_snapshot(state, cfg, places, tmp / publish.snapshot_name(generation), generation, MODELS,
                                label_cache=state_dir / "labelvecs.npz", text_encoder_factory=_text_encoder)
    manifest = build_manifest(snap, generation, MODELS)
    print(f"snapshot generation {generation}: {manifest['counts']['assets']} assets, {manifest['size'] / 1e6:.1f} MB")
    from . import verify as verify_mod
    problems, _facts = verify_mod.verify(snap, manifest)              # a snapshot that fails its own checks is never published
    if problems:
        state.finish_run(rid, "blocked", "snapshot failed verification: " + "; ".join(problems)[:300])
        print("STOPPED: the new snapshot failed its checks and was NOT published:\n  - " + "\n  - ".join(problems))
        return 4
    if args.no_publish:
        print(f"not publishing (--no-publish); file kept at {snap}")
        state.finish_run(rid, "ok", "built, not published")
        return 0
    publish.publish(dav, args.publish_dir or cfg.publish_dir, snap, manifest, cfg.keep_snapshots)
    state.kv_set("generation", generation)
    state.kv_set("published_digest", digest)
    snap.unlink(missing_ok=True)
    state.finish_run(rid, "ok", f"published generation {generation}")
    print(f"published generation {generation} to {args.publish_dir or cfg.publish_dir}")
    log.info("published generation=%d assets=%d size=%d", generation, manifest["counts"]["assets"], manifest["size"])
    return 0


def cmd_content(args: argparse.Namespace) -> int:
    """Phase 4a: content vectors for photos already in the index. Needs `uv sync --extra content`."""
    from . import content, contentrun, gpu
    cfg = Config.load(Path(args.config) if args.config else None)
    state_dir = Path(cfg.state_dir)
    _setup_logging(state_dir)
    log = logging.getLogger("bookahphotos.content")
    user, password = read_credential(args.credential or cfg.credential_target)
    dav = Dav(cfg.base_url, cfg.user or user, password, cfg.request_timeout_s, cfg.min_request_interval_s)
    state = State(state_dir / (args.state_name or "indexer.db"))
    adapter = gpu.find_adapter()
    guard = gpu.GpuGuard()
    stop = threading.Event()
    timer = threading.Timer(args.minutes * 60, stop.set) if args.minutes else None
    if timer:
        timer.daemon = True
        timer.start()
    t0 = time.time()
    res = contentrun.run_content(dav, state, state_dir / "cache", adapter, guard, stop, args.limit, workers=min(2, cfg.preview_workers), spread=args.spread)
    if timer:
        timer.cancel()
    counts = state.content_counts()
    print(f"content: {res.done} vectors made in {time.time() - t0:.0f} s ({res.from_cache} pictures from the cache, {res.from_original} originals read), "
          f"{res.no_picture} without a usable picture, {res.transient} network errors (tried again next run)")
    print(f"in total: {counts.get('ok', 0)} vectors, {counts.get('no_picture', 0)} without a picture, {len(state.pending_content(content.MODEL_NAME))} still to do")
    if res.stopped:
        print("stopped early: " + res.stopped)
    log.info("content done=%d none=%d transient=%d cache=%d original=%d stopped=%s", res.done, res.no_picture, res.transient, res.from_cache, res.from_original, res.stopped)
    return 0


def cmd_faces(args: argparse.Namespace) -> int:
    """Phase 4b: find and describe faces in the cached previews (CPU). Needs `uv sync --extra content`."""
    from . import facesrun
    cfg = Config.load(Path(args.config) if args.config else None)
    state_dir = Path(cfg.state_dir)
    _setup_logging(state_dir)
    user, password = read_credential(args.credential or cfg.credential_target)
    dav = Dav(cfg.base_url, cfg.user or user, password, cfg.request_timeout_s, cfg.min_request_interval_s)
    state = State(state_dir / (args.state_name or "indexer.db"))
    stop = threading.Event()
    timer = threading.Timer(args.minutes * 60, stop.set) if args.minutes else None
    if timer:
        timer.daemon = True
        timer.start()
    t0 = time.time()
    res = facesrun.run_faces(dav, state, state_dir / "cache", stop, args.limit, workers=args.workers)
    if timer:
        timer.cancel()
    from . import faces as faces_mod
    left = len(state.pending_faces(faces_mod.FACE_MODEL))
    print(f"faces: {res.photos} photos read in {time.time() - t0:.0f} s: {res.faces} faces found, {res.usable} usable; {res.no_picture} without a usable picture, "
          f"{res.transient} network errors (tried again next run); {left} photos still to do" + (f"; stopped early: {res.stopped}" if res.stopped else ""))
    logging.getLogger("bookahphotos.faces").info("faces photos=%d faces=%d usable=%d none=%d transient=%d left=%d", res.photos, res.faces, res.usable, res.no_picture, res.transient, left)
    return 0


def cmd_group(args: argparse.Namespace) -> int:
    """Phase 4b: group usable faces into people (stable ids), then apply Chris's ops. Reads ops from Nextcloud only with --ops; moves op files only with --move-ops."""
    from . import grouping, ops as ops_mod
    cfg = Config.load(Path(args.config) if args.config else None)
    state_dir = Path(cfg.state_dir)
    _setup_logging(state_dir)
    state = State(state_dir / (args.state_name or "indexer.db"))
    if args.regroup:
        grouping.reset_automatic(state)
        print("the automatic grouping was reset; grouping again from scratch")
    r = grouping.group(state)
    people = state.db.execute("SELECT COUNT(*) FROM persons WHERE merged_into IS NULL").fetchone()[0]
    visible = state.db.execute("SELECT COUNT(*) FROM (SELECT person_id FROM faces WHERE person_id IS NOT NULL AND rejected=0 GROUP BY person_id HAVING COUNT(*) >= 5)").fetchone()[0]
    print(f"grouping: {r.assigned_to_existing} faces newly joined people, {r.removed_joins} earlier joins taken back, {r.new_persons} new people ({r.clustered_faces} faces), {r.left_unassigned} usable faces left unassigned")
    print(f"people now: {people}, of which {visible} with 5 or more faces; biggest groups: {r.sizes}; the biggest holds {r.top_group_share:.0%} of all grouped faces"
          + ("  <-- WARNING: people may have been chained together" if r.top_group_share > 0.35 else ""))
    for path in args.ops_file or []:
        ops_list, bad = ops_mod.read_local_file(path)
        print(f"local ops file: {ops_mod.apply_ops(state, ops_list)}" + (f", {bad} unreadable lines" if bad else ""))
        r2 = grouping.group(state)             # the corrections may let more faces join the people
        print(f"grouping after the ops: {r2.assigned_to_existing} more faces joined people, {r2.left_unassigned} unassigned")
    if args.ops:
        user, password = read_credential(args.credential or cfg.credential_target)
        dav = Dav(cfg.base_url, cfg.user or user, password, cfg.request_timeout_s, cfg.min_request_interval_s)
        o = ops_mod.sync(dav, state, replay=args.replay_ops, move=args.move_ops)
        print(f"ops: {o}")
    logging.getLogger("bookahphotos.group").info("group joined=%d new=%d unassigned=%d people=%d top_share=%.3f", r.assigned_to_existing, r.new_persons, r.left_unassigned, people, r.top_group_share)
    return 0


def cmd_people_review(args: argparse.Namespace) -> int:
    from . import facereview
    cfg = Config.load(Path(args.config) if args.config else None)
    state_dir = Path(cfg.state_dir)
    state = State(state_dir / (args.state_name or "indexer.db"))
    out = state_dir / "review" / "people" / "index.html"
    s = facereview.write_review(state, state_dir / "cache", out)
    print(f"people review page ({s['shown']} of {s['groups']} groups, {s['grouped']} of {s['usable_faces']} usable faces grouped): {out}")
    return 0


def cmd_review_to_ops(args: argparse.Namespace) -> int:
    from . import reviewops
    cfg = Config.load(Path(args.config) if args.config else None)
    state = State(Path(cfg.state_dir) / (args.state_name or "indexer.db"))
    results = __import__("json").loads(Path(args.results).read_text(encoding="utf-8"))
    ops_list = reviewops.results_to_ops(state, results)
    reviewops.write_ops_file(ops_list, args.out)
    kinds = {}
    for o in ops_list:
        kinds[o["type"]] = kinds.get(o["type"], 0) + 1
    print(f"{len(ops_list)} ops written to the private file {args.out}: {kinds}")
    return 0


def cmd_memories(args: argparse.Namespace) -> int:
    """Phase 4c: find memories and trips and write the private review page. Nothing is published."""
    from . import memories, memoryreview
    cfg = Config.load(Path(args.config) if args.config else None)
    state_dir = Path(cfg.state_dir)
    state = State(state_dir / (args.state_name or "indexer.db"))
    if args.home:
        lat, lon = (float(x) for x in args.home.split(","))
        state.kv_set("home", f"{lat},{lon}")
        print(f"home place set to {lat},{lon} (stored in the private database)")
    if args.auto_home:
        state.db.execute("DELETE FROM kv WHERE key='home'")
        state.db.commit()
    places = PlaceIndex.load() if DEFAULT_FILE.exists() else None
    found, home = memories.find_memories(state, places)
    out = state_dir / "review" / "memories"
    counts = memoryreview.write_review(found, home, places, state_dir / "cache", out)
    print(f"home place: {memoryreview.home_description(home, places)}")
    print("memories found: " + ", ".join(f"{v} {k.replace('_', ' ')}" for k, v in counts.items()))
    print(f"review page (private, real photos): {out / 'index.html'}")
    return 0


def cmd_verify(args: argparse.Namespace) -> int:
    """Check a snapshot file read only. Without --file, builds one from the private database into the state folder's tmp (not published) and checks that."""
    from . import verify as verify_mod
    cfg = Config.load(Path(args.config) if args.config else None)
    state_dir = Path(cfg.state_dir)
    path = Path(args.file) if args.file else None
    manifest = None
    if path is None:
        state = State(state_dir / (args.state_name or "indexer.db"))
        places = PlaceIndex.load() if DEFAULT_FILE.exists() else None
        tmp = state_dir / "tmp"
        tmp.mkdir(parents=True, exist_ok=True)
        path = build.build_snapshot(state, cfg, places, tmp / "verify-snapshot.db", int(state.kv_get("generation", "0")) + 1, MODELS,
                                    label_cache=state_dir / "labelvecs.npz", text_encoder_factory=_text_encoder)
        manifest = build_manifest(path, 0, MODELS)
        print(f"built {path} ({path.stat().st_size / 1e6:.1f} MB), not published")
    problems, facts = verify_mod.verify(path, manifest)
    print("counts: " + ", ".join(f"{k} {v}" for k, v in facts.items()))
    if problems:
        print("PROBLEMS:")
        for p in problems:
            print("  - " + p)
        return 1
    print("verified: no problems found")
    return 0


def cmd_search_check(args: argparse.Namespace) -> int:
    """Phase 4d: search quality on the whole library, using the vectors in a snapshot file. --score FILE scores Chris's clicks."""
    from . import content, searchcheck
    cfg = Config.load(Path(args.config) if args.config else None)
    state_dir = Path(cfg.state_dir)
    out = state_dir / "review" / "search-whole"
    if args.small:
        snap = Path(args.snapshot) if args.snapshot else state_dir / "tmp" / "verify-snapshot.db"
        sm = out.parent / "search-small"
        r = searchcheck.write_small_page(snap, out / "queries.json", state_dir / "cache", sm, Path(args.asset), Path(args.small_model))
        print(f"phone model vs full model, top 10 on the whole library: mean overlap {r['mean_overlap']} of 10 (lowest {r['min_overlap']}); {r['new_photos']} new photos to judge")
        print(f"page (private, real photos): {sm / 'index.html'}")
        return 0
    if args.score_small:
        sm = out.parent / "search-small"
        s = searchcheck.score_small(sm / "queries.json", Path(args.score_small), Path(args.old_clicks))
        print(f"phone model, accepted of 10: Greek {s['greek']}, English {s['english']}, extra phrases {s['extras']}, all {s['all']}  (full model on the whole library: 9.87, 10.0, 10.0, 9.95)")
        print("weakest questions: " + "; ".join(f"{q} = {n}" for q, n in s["worst"]))
        return 0
    if args.score:
        s = searchcheck.score(out / "queries.json", Path(args.score))
        print(f"accepted of 10 on the whole library: Greek {s['greek']}, English {s['english']}, extra phrases {s['extras']}, all {s['all']}  (Phase 0 sample: 8.67, 8.64, 9.44, 8.84)")
        print("weakest questions: " + "; ".join(f"{q} = {n}" for q, n in s["worst"]))
        return 0
    snap = Path(args.snapshot) if args.snapshot else state_dir / "tmp" / "verify-snapshot.db"
    searchcheck.write_page(snap, Path(args.queries), state_dir / "cache", out, content.TextEncoder(None))
    print(f"search check page (private, real photos): {out / 'index.html'}")
    return 0


def cmd_daily(args: argparse.Namespace) -> int:
    """Everything that new photos need, once: for Windows Task Scheduler (not installed unless Chris says yes)."""
    from . import daily
    cfg = Config.load(Path(args.config) if args.config else None)
    state_dir = Path(cfg.state_dir)
    _setup_logging(state_dir)
    steps = daily.steps_for(args.minutes)
    extra = ["--state-name", args.state_name] if args.state_name else []
    steps = [[s[0]] + extra + s[1:] if s[0] in ("content", "faces", "group", "run") and extra else s for s in steps]
    t0 = time.time()
    rc, what = daily.run_daily(cfg.base_url, state_dir / "daily.lock", steps, lambda argv: main(argv))
    msg = f"daily job {what} ({time.time() - t0:.0f} s)"
    print(msg)
    logging.getLogger("bookahphotos.daily").info(msg)
    return rc


def cmd_publish_models(args: argparse.Namespace) -> int:
    """Phase 5a: build the phone's tokenizer file and upload it with the small text model to BookahPhotos/models/ (read the 5a report first; needs Chris's yes, given 13 Nov)."""
    import shutil
    from . import phone_fixtures, phone_tokenizer, publish_models
    cfg = Config.load(Path(args.config) if args.config else None)
    state_dir = Path(cfg.state_dir)
    _setup_logging(state_dir)
    build_dir = state_dir / "models-build"
    build_dir.mkdir(parents=True, exist_ok=True)
    info = phone_tokenizer.build_asset(phone_fixtures.TOKENIZER_JSON, phone_fixtures.OLD2NEW, build_dir / "text_tokenizer.bin")
    small = phone_fixtures.SPIKE / r"shrunk\text_small.onnx"
    files = {"text_small.onnx": small, "text_tokenizer.bin": build_dir / "text_tokenizer.bin"}
    print(f"tokenizer file: {info['size'] / 1e6:.1f} MB, {info['pieces']} pieces; text model {small.stat().st_size / 1e6:.0f} MB")
    if args.build_only:
        shutil.copy(small, build_dir / "text_small.onnx")
        print(f"built only (not uploaded): {build_dir}")
        return 0
    user, password = read_credential(args.credential or cfg.credential_target)
    dav = Dav(cfg.base_url, cfg.user or user, password, max(cfg.request_timeout_s, 600.0), cfg.min_request_interval_s)
    m = publish_models.publish_models(dav, files, directory=f"{publish_models.MODEL_ID}-v{publish_models.VERSION}")
    print("published to BookahPhotos/models/: " + ", ".join(f"{f['name']} ({f['size'] / 1e6:.1f} MB)" for f in m["files"]))
    return 0


def cmd_phone_fixtures(args: argparse.Namespace) -> int:
    from . import phone_fixtures
    if args.encoder_out:
        n = phone_fixtures.write_encoder_expected(Path(args.encoder_out), Path(args.asset))
        print(f"{n} expected question vectors written to {args.encoder_out}")
        return 0
    n = phone_fixtures.write_fixtures(Path(args.out), n_fuzz=args.fuzz)
    print(f"{n} tokenizer test cases written to {args.out}")
    return 0


def cmd_labels_review(args: argparse.Namespace) -> int:
    """Write the private label review page (real photos: stays in the state folder)."""
    from . import content, labelreview
    cfg = Config.load(Path(args.config) if args.config else None)
    state_dir = Path(cfg.state_dir)
    state = State(state_dir / (args.state_name or "indexer.db"))
    from . import labels
    out = state_dir / ("review/labels2" if args.round2 else "review/labels")
    summary = labelreview.write_review(state, state_dir / "cache", out, content.TextEncoder(None), out / "agreed_thresholds.json",
                                       keep=labels.KEEP_AFTER_REVIEW if args.round2 else None)
    tagged = sum(1 for v in summary.values() if v["tagged"])
    print(f"review page for {len(summary)} labels ({tagged} with at least one picture): {out / 'index.html'}")
    return 0


def cmd_fake(args: argparse.Namespace) -> int:
    p = make_fake_snapshot(Path(args.out), args.count, args.seed)
    print(f"fake snapshot with {args.count} synthetic items: {p} ({p.stat().st_size / 1e6:.1f} MB), schema {schema.SCHEMA_MAJOR}.{schema.SCHEMA_MINOR}")
    return 0


def main(argv: list[str] | None = None) -> int:
    for stream in (sys.stdout, sys.stderr):  # Greek text in reports must not crash a Windows console
        try: stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception: pass
    ap = argparse.ArgumentParser(prog="bookahphotos-indexer", description=f"BookahPhotos indexer {__version__} (Phase 1: metadata only)")
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run", help="scan the photo roots, read metadata, make placeholders, publish a snapshot")
    r.add_argument("--config"), r.add_argument("--roots", nargs="+", help="override the photo roots (for example BookahPhotos/test-root)")
    r.add_argument("--credential", help="Windows Credential Manager target holding the app password")
    r.add_argument("--publish-dir", help="override where the snapshot is published")
    r.add_argument("--state-name", help="use a different private state file (for tests)")
    r.add_argument("--accept-deletions", action="store_true", help="proceed although more than the safeguard share of photos disappeared")
    r.add_argument("--skip-previews", action="store_true"), r.add_argument("--max-previews", type=int), r.add_argument("--max-metadata", type=int)
    r.add_argument("--minutes", type=float, help="stop making placeholders after this many minutes (resumable)")
    r.add_argument("--reread-metadata", action="store_true", help="read the metadata of every known file again (after a rule change)")
    r.add_argument("--no-publish", action="store_true"), r.add_argument("--force-publish", action="store_true")
    r.add_argument("--no-snapshot", action="store_true", help="stop after scanning, metadata and placeholders (used by `daily`)")
    r.set_defaults(fn=cmd_run)
    c = sub.add_parser("content", help="Phase 4a: make search vectors for photos already in the index (needs the content extra)")
    c.add_argument("--config"), c.add_argument("--credential"), c.add_argument("--state-name")
    c.add_argument("--limit", type=int, help="at most this many pictures (for a first small run)")
    c.add_argument("--minutes", type=float, help="stop after this many minutes (resumable)")
    c.add_argument("--spread", action="store_true", help="with --limit: pick the pictures evenly over the whole library (for tuning tags)")
    c.set_defaults(fn=cmd_content)
    fa = sub.add_parser("faces", help="Phase 4b: find and describe faces in the cached previews (CPU)")
    fa.add_argument("--config"), fa.add_argument("--credential"), fa.add_argument("--state-name")
    fa.add_argument("--limit", type=int), fa.add_argument("--minutes", type=float), fa.add_argument("--workers", type=int)
    fa.set_defaults(fn=cmd_faces)
    gr = sub.add_parser("group", help="Phase 4b: group faces into people with stable ids, then apply ops")
    gr.add_argument("--config"), gr.add_argument("--credential"), gr.add_argument("--state-name")
    gr.add_argument("--regroup", action="store_true", help="forget the automatic grouping and make it again (refused when manual choices exist)")
    gr.add_argument("--ops-file", nargs="+", help="apply local op files (.jsonl) too, for example the corrections from a review page")
    gr.add_argument("--ops", action="store_true", help="also read the op files in BookahPhotos/ops/ and apply them (read only unless --move-ops)")
    gr.add_argument("--replay-ops", action="store_true", help="with --ops: also replay the archived ops (after the private database was rebuilt)")
    gr.add_argument("--move-ops", action="store_true", help="with --ops: move applied op files to BookahPhotos/ops/applied/ (needs Chris's yes)")
    gr.set_defaults(fn=cmd_group)
    pr = sub.add_parser("people-review", help="Phase 4b: write the private review page of the biggest people groups")
    pr.add_argument("--config"), pr.add_argument("--state-name")
    pr.set_defaults(fn=cmd_people_review)
    ro = sub.add_parser("review-to-ops", help="turn the people review page's results file into ops (the output holds names: private folder only)")
    ro.add_argument("--config"), ro.add_argument("--state-name")
    ro.add_argument("--results", required=True), ro.add_argument("--out", required=True)
    ro.set_defaults(fn=cmd_review_to_ops)
    me = sub.add_parser("memories", help="Phase 4c: find memories and trips, write the private review page (nothing is published)")
    me.add_argument("--config"), me.add_argument("--state-name")
    me.add_argument("--home", help="correct the home place: 'latitude,longitude' (kept in the private database)")
    me.add_argument("--auto-home", action="store_true", help="forget a corrected home place and find it from the photos again")
    me.set_defaults(fn=cmd_memories)
    vs = sub.add_parser("verify-snapshot", help="Phase 4d: check a snapshot read only (builds one without publishing when --file is not given)")
    vs.add_argument("--config"), vs.add_argument("--state-name"), vs.add_argument("--file")
    vs.set_defaults(fn=cmd_verify)
    sc = sub.add_parser("search-check", help="Phase 4d: the Phase 0 questions on the whole library (private page); --score scores Chris's clicks")
    sc.add_argument("--config"), sc.add_argument("--snapshot")
    sc.add_argument("--queries", default=r"C:\BookahPC\bookahphotos\spike\out\siglip2_top10.json", help="the Phase 0 question list")
    sc.add_argument("--score", help="the results file downloaded from the page")
    sc.add_argument("--small", action="store_true", help="the PHONE's shrunk model on the whole library vs the full model (page with only the new photos)")
    sc.add_argument("--score-small", help="score the phone-model page: the results file downloaded from it"), sc.add_argument("--old-clicks", help="with --score-small: the first page's results file")
    sc.add_argument("--asset", default=r"C:\BookahPC\bookahphotos\models-build\text_tokenizer.bin")
    sc.add_argument("--small-model", default=r"C:\BookahPC\bookahphotos\spike\shrunk\text_small.onnx")
    sc.set_defaults(fn=cmd_search_check)
    da = sub.add_parser("daily", help="Phase 4d: everything new photos need, in order (for Task Scheduler; installed only with Chris's yes)")
    da.add_argument("--config"), da.add_argument("--state-name")
    da.add_argument("--minutes", type=float, default=60.0, help="total time budget; each step gets a share")
    da.set_defaults(fn=cmd_daily)
    pm = sub.add_parser("publish-models", help="Phase 5a: upload the phone's search model and tokenizer file to BookahPhotos/models/")
    pm.add_argument("--config"), pm.add_argument("--credential")
    pm.add_argument("--build-only", action="store_true", help="only build the files in the state folder, upload nothing")
    pm.set_defaults(fn=cmd_publish_models)
    pf = sub.add_parser("phone-fixtures", help="Phase 5a: write the tokenizer test cases (truth from the PC tokenizer) for the Kotlin tests")
    pf.add_argument("--out"), pf.add_argument("--fuzz", type=int, default=3000)
    pf.add_argument("--encoder-out", help="instead: write the expected question vectors of text_small.onnx for the on-device test")
    pf.add_argument("--asset", default=r"C:\BookahPC\bookahphotos\models-build\text_tokenizer.bin")
    pf.set_defaults(fn=cmd_phone_fixtures)
    lr = sub.add_parser("labels-review", help="Phase 4a: write the private review page of the tag labels")
    lr.add_argument("--config"), lr.add_argument("--state-name")
    lr.add_argument("--round2", action="store_true", help="tighten the labels Chris marked as wrong and show only those")
    lr.set_defaults(fn=cmd_labels_review)
    f = sub.add_parser("fake-snapshot", help="write a fully synthetic snapshot for app development")
    f.add_argument("--out", required=True), f.add_argument("--count", type=int, default=50_000), f.add_argument("--seed", type=int, default=1)
    f.set_defaults(fn=cmd_fake)
    args = ap.parse_args(argv)
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main())
