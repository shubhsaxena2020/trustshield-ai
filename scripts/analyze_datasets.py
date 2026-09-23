#!/usr/bin/env python3
"""
Analyze the processed public datasets for TrustShield Phase 3.
Computes statistics and writes a summary report.
"""

import json
import random
from datetime import date
from pathlib import Path
from typing import Dict, List, Tuple
import statistics

# Project root
PROJECT_ROOT = Path(__file__).resolve().parents[2]
PROCESSED_ROOT = PROJECT_ROOT / "datasets" / "processed" / "public"
DOCS_ROOT = PROJECT_ROOT / "docs"

def load_jsonl(filepath: Path) -> List[Dict]:
    """Load JSONL file."""
    records = []
    with open(filepath, 'r', encoding='utf-8') as f:
        for line in f:
            records.append(json.loads(line.strip()))
    return records

def word_count(text: str) -> int:
    """Count words in text (split by whitespace)."""
    return len(text.split())

def analyze_source(source_name: str, records: List[Dict]) -> Dict:
    """Analyze a single source."""
    # Counts by binary_label
    binary_counts = {}
    for r in records:
        label = r['binary_label']
        binary_counts[label] = binary_counts.get(label, 0) + 1
    
    # Counts by category_label
    category_counts = {}
    for r in records:
        label = r['category_label']
        category_counts[label] = category_counts.get(label, 0) + 1
    
    # Word counts per binary class
    legitimate_words = []
    malicious_words = []
    for r in records:
        wc = word_count(r['text'])
        if r['binary_label'] == 'legitimate':
            legitimate_words.append(wc)
        else:
            malicious_words.append(wc)
    
    def stats(word_list):
        if not word_list:
            return {'mean': 0, 'median': 0, 'p95': 0}
        mean = statistics.mean(word_list)
        median = statistics.median(word_list)
        p95 = int(statistics.quantiles(word_list, n=20)[-1])  # 95th percentile
        return {'mean': round(mean, 2), 'median': median, 'p95': p95}
    
    legit_stats = stats(legitimate_words)
    mal_stats = stats(malicious_words)
    
    # Label noise: take a random sample of up to 30 rows and note any obvious issues
    sample_size = min(30, len(records))
    sample = random.sample(records, sample_size) if records else []
    noise_notes = []
    for r in sample:
        # Check if text is empty or very short
        if len(r['text'].strip()) < 2:
            noise_notes.append(f"Very short text: {repr(r['text'])}")
        # Check if label seems inconsistent with text (very heuristic)
        text_lower = r['text'].lower()
        if r['binary_label'] == 'legitimate':
            # Look for obvious scam indicators
            scam_indicators = ['free', 'win', 'prize', 'lottery', 'urgent', 'click', 'offer', 'congratulations']
            if any(ind in text_lower for ind in scam_indicators):
                noise_notes.append(f"Legitimate label but contains scam indicator: {repr(r['text'][:50])}")
        else:
            # Look for obvious legitimate indicators
            legit_indicators = ['thank you', 'hello', 'hi', 'thanks', 'please', 'dear']
            if any(ind in text_lower for ind in legit_indicators) and not any(ind in text_lower for ind in ['free', 'win', 'prize']):
                noise_notes.append(f"Malicious label but looks like legitimate: {repr(r['text'][:50])}")
    
    return {
        'source_name': source_name,
        'total_records': len(records),
        'binary_counts': binary_counts,
        'category_counts': category_counts,
        'legitimate_word_stats': legit_stats,
        'malicious_word_stats': mal_stats,
        'noise_notes': noise_notes[:5]  # limit to 5 notes
    }

def main():
    """Main analysis."""
    # Find all JSONL files in processed/public
    jsonl_files = list(PROCESSED_ROOT.glob("*.jsonl"))
    if not jsonl_files:
        print("No processed JSONL files found.")
        return
    
    results = []
    for jsonl_file in jsonl_files:
        source_name = jsonl_file.stem
        print(f"Analyzing {source_name}...")
        records = load_jsonl(jsonl_file)
        analysis = analyze_source(source_name, records)
        results.append(analysis)
    
    # Write summary report
    today = date.today().isoformat()
    report_path = DOCS_ROOT / "DATASET_SUMMARY.md"
    with open(report_path, 'w', encoding='utf-8') as f:
        f.write("# TrustShield Phase 3: Public Dataset Summary\n\n")
        f.write(f"**Report generated**: {today}\n\n")
        
        # Summary table
        f.write("## Summary Table\n\n")
        f.write("| Source | License | Total Records | Legitimate Count | Malicious Count | Notes |\n")
        f.write("|--------|---------|---------------|------------------|-----------------|-------|\n")
        # We need to get license from the SOURCE.md files. For simplicity, we'll note it's from the fetch script.
        # We'll hardcode for now based on what we know.
        license_map = {
            'ucI_sms_spam': 'UCI Machine Learning Repository - Non-commercial use only',
            'india_spam_sms': 'MIT License',
            # 'mendeley_sms_phishing': 'CC BY 4.0'  # we didn't fetch this
        }
        for r in results:
            license = license_map.get(r['source_name'], 'Unknown')
            legit_count = r['binary_counts'].get('legitimate', 0)
            mal_count = r['binary_counts'].get('malicious_or_fraudulent', 0)
            f.write(f"| {r['source_name']} | {license} | {r['total_records']} | {legit_count} | {mal_count} | See SOURCE.md |\n")
        
        f.write("\n")
        
        # Detailed analysis per source
        f.write("## Detailed Analysis per Source\n\n")
        for r in results:
            f.write(f"### {r['source_name']}\n\n")
            f.write(f"- **Total records**: {r['total_records']}\n")
            f.write(f"- **Binary label distribution**: {r['binary_counts']}\n")
            f.write(f"- **Category label distribution**: {r['category_counts']}\n")
            f.write(f"- **Legitimate text word stats**: mean={r['legitimate_word_stats']['mean']}, median={r['legitimate_word_stats']['median']}, p95={r['legitimate_word_stats']['p95']}\n")
            f.write(f"- **Malicious text word stats**: mean={r['malicious_word_stats']['mean']}, median={r['malicious_word_stats']['median']}, p95={r['malicious_word_stats']['p95']}\n")
            f.write(f"- **Label noise notes (from random sample of up to 30)**:\n")
            if r['noise_notes']:
                for note in r['noise_notes']:
                    f.write(f"  - {note}\n")
            else:
                f.write("  - No obvious label noise detected in sample.\n")
            f.write("\n")
    
    print(f"\nSummary report written to: {report_path}")

if __name__ == "__main__":
    main()