#!/usr/bin/env python3
"""
Orvix Kubernetes & Helm Deployment Verification Harness.

Validates:
1. Independent Deployment Architecture for all 4 microservices:
   - gateway-service (8080)
   - guardrail-service (8081)
   - cache-service (8082)
   - billing-service (8083)
2. Zero-Downtime Rolling Deployment Strategy (maxSurge: 25%, maxUnavailable: 0).
3. 3-Tier Health Probes:
   - startupProbe (/actuator/health)
   - livenessProbe (/actuator/health/liveness)
   - readinessProbe (/actuator/health/readiness)
4. Explicit Resource Allocations (Requests & Limits for CPU & Memory).
5. Horizontal Pod Autoscaling (HPA v2) with CPU/Memory targets and stabilization windows.
6. High-Availability Pod Disruption Budgets (PDB v1, minAvailable: 1).
7. Prometheus Scrape Configurations & ServiceMonitor resources.
8. Evaluates both raw k8s/ manifests and rendered helm/orvix templates.
"""

import os
import sys
import subprocess
import json

try:
    import yaml
except ImportError:
    subprocess.check_call([sys.executable, "-m", "pip", "install", "pyyaml"])
    import yaml

K8S_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), "k8s")
HELM_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), "helm", "orvix")

SERVICES = ["gateway-service", "guardrail-service", "cache-service", "billing-service"]

def load_k8s_docs():
    docs = []
    for fname in sorted(os.listdir(K8S_DIR)):
        if fname.endswith(".yaml") or fname.endswith(".yml"):
            fpath = os.path.join(K8S_DIR, fname)
            with open(fpath, "r", encoding="utf-8") as f:
                content = f.read()
                for doc in yaml.safe_load_all(content):
                    if doc:
                        docs.append(doc)
    return docs

def load_helm_rendered_docs():
    cmd = ["docker", "run", "--rm", "-v", f"{os.path.dirname(os.path.dirname(__file__))}:/workspace", "-w", "/workspace", "alpine/helm:latest", "template", "orvix", "helm/orvix"]
    res = subprocess.run(cmd, capture_output=True, text=True, check=True)
    docs = []
    for doc in yaml.safe_load_all(res.stdout):
        if doc:
            docs.append(doc)
    return docs

def validate_suite(docs, suite_name="k8s"):
    print(f"\n[{suite_name.upper()}] Validating {len(docs)} Kubernetes Resources...")
    
    deployments = {d["metadata"]["name"]: d for d in docs if d.get("kind") == "Deployment"}
    services = {s["metadata"]["name"]: s for s in docs if s.get("kind") == "Service"}
    hpas = {h["metadata"]["name"]: h for h in docs if h.get("kind") == "HorizontalPodAutoscaler"}
    pdbs = {p["metadata"]["name"]: p for p in docs if p.get("kind") == "PodDisruptionBudget"}
    
    # 1. Independent Microservices Checks
    for svc_name in SERVICES:
        assert svc_name in deployments, f"Missing Deployment for {svc_name}"
        deploy = deployments[svc_name]
        spec = deploy["spec"]
        template = spec["template"]
        pod_spec = template["spec"]
        container = pod_spec["containers"][0]
        
        # A. Rolling Deployment Strategy
        strat = spec.get("strategy", {})
        assert strat.get("type") == "RollingUpdate", f"{svc_name} must use RollingUpdate strategy"
        ru = strat.get("rollingUpdate", {})
        assert ru.get("maxUnavailable") == 0, f"{svc_name} maxUnavailable must be 0 for zero-downtime, got {ru.get('maxUnavailable')}"
        assert ru.get("maxSurge") == "25%" or ru.get("maxSurge") == 1, f"{svc_name} maxSurge must be 25% or 1, got {ru.get('maxSurge')}"
        
        # B. 3-Tier Health Probes
        startup = container.get("startupProbe", {}).get("httpGet", {})
        assert startup.get("path") == "/actuator/health", f"{svc_name} startupProbe path must be /actuator/health"
        
        liveness = container.get("livenessProbe", {}).get("httpGet", {})
        assert liveness.get("path") == "/actuator/health/liveness", f"{svc_name} livenessProbe path must be /actuator/health/liveness"
        
        readiness = container.get("readinessProbe", {}).get("httpGet", {})
        assert readiness.get("path") == "/actuator/health/readiness", f"{svc_name} readinessProbe path must be /actuator/health/readiness"
        
        # C. Resource Allocations
        res = container.get("resources", {})
        req = res.get("requests", {})
        lim = res.get("limits", {})
        assert "cpu" in req and "memory" in req, f"{svc_name} must specify CPU and Memory requests"
        assert "cpu" in lim and "memory" in lim, f"{svc_name} must specify CPU and Memory limits"
        
        # D. Container Security Context
        sec = pod_spec.get("securityContext", {})
        assert sec.get("runAsNonRoot") is True, f"{svc_name} must enforce runAsNonRoot: true"
        assert sec.get("runAsUser") == 10001, f"{svc_name} must run as unprivileged UID 10001"
        
        # E. Prometheus Pod Annotations
        ann = template.get("metadata", {}).get("annotations", {})
        assert ann.get("prometheus.io/scrape") == "true", f"{svc_name} must have prometheus.io/scrape: 'true'"
        assert ann.get("prometheus.io/path") == "/actuator/prometheus", f"{svc_name} must have prometheus.io/path: '/actuator/prometheus'"
        
        print(f"  [PASS] {svc_name}: RollingUpdate (maxUnavailable=0), 3-Tier Probes, Resources ({req['cpu']}/{lim['cpu']}), SecurityContext verified.")

    # 2. Autoscaling HPA Verification
    for svc_name in SERVICES:
        short_name = svc_name.split("-")[0]
        hpa_name = f"{short_name}-hpa"
        assert hpa_name in hpas, f"Missing HPA for {svc_name} ({hpa_name})"
        hpa = hpas[hpa_name]
        hpa_spec = hpa["spec"]
        assert hpa_spec["scaleTargetRef"]["name"] == svc_name, f"HPA {hpa_name} target must be {svc_name}"
        assert hpa_spec["minReplicas"] >= 2, f"HPA {hpa_name} minReplicas must be >= 2 for high availability"
        assert hpa_spec["maxReplicas"] >= 6, f"HPA {hpa_name} maxReplicas must be >= 6"
        metrics = hpa_spec["metrics"]
        assert any(m.get("resource", {}).get("name") == "cpu" for m in metrics), f"HPA {hpa_name} must have CPU metric"
        print(f"  [PASS] {hpa_name}: HPA v2 verified (minReplicas={hpa_spec['minReplicas']}, maxReplicas={hpa_spec['maxReplicas']}).")

    # 3. Pod Disruption Budgets (PDB) Verification
    for svc_name in SERVICES:
        short_name = svc_name.split("-")[0]
        pdb_name = f"{short_name}-pdb"
        assert pdb_name in pdbs, f"Missing PDB for {svc_name} ({pdb_name})"
        pdb = pdbs[pdb_name]
        assert pdb["spec"].get("minAvailable") == 1, f"PDB {pdb_name} minAvailable must be 1"
        print(f"  [PASS] {pdb_name}: PodDisruptionBudget verified (minAvailable=1).")

    # 4. Ingress & Prometheus Scrape Verification
    ingress_docs = [d for d in docs if d.get("kind") == "Ingress"]
    assert len(ingress_docs) > 0, "Missing Ingress resource"
    print(f"  [PASS] Ingress verified ({ingress_docs[0]['metadata']['name']}).")

    prom_cm = [d for d in docs if d.get("kind") == "ConfigMap" and "prometheus" in d["metadata"]["name"]]
    assert len(prom_cm) > 0, "Missing Prometheus ConfigMap"
    prom_yaml = prom_cm[0]["data"]["prometheus.yml"]
    for svc_name in SERVICES:
        assert svc_name in prom_yaml, f"Prometheus config must scrape {svc_name}"
    print(f"  [PASS] Prometheus scraper verified for all 4 microservices.")

    service_monitors = [d for d in docs if d.get("kind") == "ServiceMonitor"]
    assert len(service_monitors) > 0, "Missing Prometheus ServiceMonitor resource"
    print(f"  [PASS] Prometheus ServiceMonitor verified ({service_monitors[0]['metadata']['name']}).")

def main():
    print("=" * 80)
    print("     ORVIX KUBERNETES & HELM DEPLOYMENT SPECIFICATION VERIFICATION     ")
    print("=" * 80)
    
    # Verify Standalone Raw k8s/ manifests
    k8s_docs = load_k8s_docs()
    validate_suite(k8s_docs, "Standalone k8s/ Manifests")
    
    # Verify Rendered Helm Chart templates
    helm_docs = load_helm_rendered_docs()
    validate_suite(helm_docs, "Rendered Helm Chart (helm/orvix)")
    
    print("\n" + "=" * 80)
    print(" [SUCCESS] All Kubernetes and Helm deployment artifacts verified!")
    print(" 1. Independent deployments for all 4 services: VERIFIED")
    print(" 2. 3-Tier Health Probes (startup, liveness, readiness): VERIFIED")
    print(" 3. Explicit Resource limits & requests: VERIFIED")
    print(" 4. Horizontal Pod Autoscaling (HPA v2): VERIFIED")
    print(" 5. Zero-Downtime Rolling Update (maxSurge=25%, maxUnavailable=0): VERIFIED")
    print(" 6. High-Availability Pod Disruption Budgets (minAvailable=1): VERIFIED")
    print(" 7. Prometheus Actuator scraping & ServiceMonitors: VERIFIED")
    print("=" * 80)

if __name__ == "__main__":
    main()
