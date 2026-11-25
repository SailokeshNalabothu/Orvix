# Architecture Decision Record 013 (ADR-013)
# Production Kubernetes Deployment: Independent Microservices, 3-Tier Health Probes, Explicit Resource Limits, Autoscaling (HPA v2), Zero-Downtime Rolling Deployments & Prometheus Observability

| Metadata | Value |
| :--- | :--- |
| **Status** | **Accepted & Empirically Verified** |
| **Author** | Orvix Cloud Native, Platform Engineering & SRE Team |
| **Date** | 2026-11-25 |
| **Scope** | Independent Microservice Deployments, Zero-Downtime Rolling Update Strategy (`maxUnavailable: 0`), 3-Tier Spring Boot Actuator Probes, Explicit Compute Allocation, Horizontal Pod Autoscaling (HPA v2), Pod Disruption Budgets (PDB), Prometheus Monitoring & Helm Packaging |
| **Target Invariant** | **Zero-Downtime Availability ($A = 1.0$), Resilient Self-Healing without False Evictions, and Multi-Tier Horizontal Autoscaling under Load** |
| **Cross-References** | [ADR-001 (Redis Rate Limiting)](05-ADR.md#adr-001), [ADR-008 (Performance Benchmarks)](08-PERFORMANCE-BENCHMARK.md), [ADR-010 (Chaos Engineering)](10-CHAOS-ENGINEERING-AND-RECOVERY.md) |

---

## 1. Context & Architectural Problem Statement

In production cloud-native enterprise deployments, monolithic or loosely coupled microservices deployed without rigorous container orchestration specifications suffer from four catastrophic operational failure modes:

1. **Premature Pod Termination During JVM Warmup:** Java 21 applications utilizing Spring Boot and class-path scanning require 10–25 seconds of JIT compilation and context initialization. If an orchestrator relies solely on naive liveness probes, pods are killed repeatedly in a crash-loop before finishing initialization.
2. **"Noisy Neighbor" Contention & OOM Kills:** Microservices lacking explicit CPU and memory requests/limits can consume unbounded node memory, causing the Linux Kernel OOM killer to terminate critical financial accounting or edge routing processes.
3. **Downtime During Deployments:** Naive deployment rolling updates with default `maxUnavailable: 25%` terminate running pods before replacement pods have successfully warmed up and connected to databases/Kafka brokers, causing dropped customer requests.
4. **Flapping Under Spiky Traffic:** Autoscalers lacking stabilization windows and gradual scale-down policies oscillate erratically ("thrashing"), causing unnecessary pod churn and degraded cache hit rates.

To address these requirements, Orvix provides a complete production **Kubernetes & Helm Deployment Architecture** implementing independent lifecycle management, 3-tier health checking, strict resource bounding, horizontal pod autoscaling, zero-downtime rolling deployment strategies, and Prometheus monitoring.

---

## 2. Independent Service Architecture

The Orvix platform decouples its four core microservices into independent Kubernetes Deployments, Services, Autoscalers, and Disruption Budgets:

```
                                    ┌────────────────────────┐
                                    │     Ingress NGINX      │
                                    │ (host: orvix.local /)  │
                                    └───────────┬────────────┘
                                                │
                                                ▼
                                    ┌────────────────────────┐
                                    │ gateway-service (8080) │
                                    │  HPA: 2-10 replicas    │
                                    │  PDB: minAvailable 1   │
                                    └────┬──────────┬────────┘
                    Sync (Fail-Closed)   │          │  Sync (Fail-Open)
                ┌────────────────────────┘          └────────────────────────┐
                ▼                                                            ▼
    ┌────────────────────────┐                                   ┌────────────────────────┐
    │ guardrail-service:8081 │                                   │   cache-service:8082   │
    │   HPA: 2-8 replicas    │                                   │   HPA: 2-8 replicas    │
    │   PDB: minAvailable 1  │                                   │   PDB: minAvailable 1  │
    └───────────┬────────────┘                                   └───────────┬────────────┘
                │                                                            │
                ▼                                                            ▼
        PostgreSQL (guardrail_db)                                    PostgreSQL (cache_db pgvector)
                
                │
                │ Asynchronous UsageRecordedEvent (Kafka)
                ▼
    ┌────────────────────────┐
    │ billing-service (8083) │
    │   HPA: 2-6 replicas    │
    │   PDB: minAvailable 1  │
    └───────────┬────────────┘
                │
                ▼
        PostgreSQL (billing_db)
```

### Microservice Deployment Topology

| Microservice | Container Port | Primary Workload | Ingress / Routing | DB / Broker Dependency |
| :--- | :---: | :--- | :--- | :--- |
| **`gateway-service`** | `8080` | Virtual Thread Edge Router, Lua Token Bucket, Fallback Buffer | External via Ingress (`/`) | Redis 7, Kafka 3.7 |
| **`guardrail-service`** | `8081` | PII Redaction, CAT 1–6 Injection Firewall, Tenant Policies | Internal ClusterIP (`:8081`) | PostgreSQL (`guardrail_db`) |
| **`cache-service`** | `8082` | Semantic Cache, 768-dim Embedding Cosine Search | Internal ClusterIP (`:8082`) | PostgreSQL (`cache_db` pgvector) |
| **`billing-service`** | `8083` | Double-Entry Ledger, Outbox Staging, 3-Tier Reconciliation | Internal ClusterIP (`:8083`) | PostgreSQL (`billing_db`), Kafka 3.7 |

---

## 3. The 3-Tier Health Probe Architecture

To eliminate premature pod restarts while guaranteeing instant detection of deadlocks, every microservice container defines three coordinated probes backed by Spring Boot Actuator:

```
Time ──►  0s                       10s                     20s-30s                      Run-Time
          │                         │                        │                             │
          └─ Pod Container Spawns   └─ startupProbe Begins   └─ startupProbe Passes        │
                                       (period: 5s, fThr: 24)   ├─ readinessProbe Active ◄─┤ (traffic enabled)
                                                                └─ livenessProbe Active  ◄─┘ (self-healing)
```

1. **`startupProbe` (`/actuator/health`):**
   - *Purpose:* Shields the JVM during JIT compilation, Spring context initialization, and Flyway schema migrations.
   - *Parameters:* `initialDelaySeconds: 10`, `periodSeconds: 5`, `timeoutSeconds: 3`, `failureThreshold: 24` (allows up to $10 + 24 \times 5 = 130\text{ seconds}$ for slow node starts before killing the pod).
   - *Behavior:* Liveness and readiness checks remain dormant until `startupProbe` succeeds.
2. **`livenessProbe` (`/actuator/health/liveness`):**
   - *Purpose:* Detects unrecoverable deadlocks or memory corruption requiring container restart.
   - *Parameters:* `periodSeconds: 10`, `timeoutSeconds: 3`, `failureThreshold: 3`.
3. **`readinessProbe` (`/actuator/health/readiness`):**
   - *Purpose:* Controls traffic routing in Kubernetes Endpoints. If a service loses connectivity to PostgreSQL, Redis, or Kafka, readiness fails and traffic is routed to healthy replicas.
   - *Parameters:* `periodSeconds: 5`, `timeoutSeconds: 3`, `failureThreshold: 2`, `successThreshold: 1`.

---

## 4. Explicit Compute Allocations & Resource Bounds

To prevent "Noisy Neighbor" resource starvation and Out-Of-Memory (OOM) termination:

| Service | CPU Request | CPU Limit | Memory Request | Memory Limit | JVM Memory Tuning |
| :--- | :---: | :---: | :---: | :---: | :--- |
| **`gateway-service`** | `250m` | `1000m` | `512Mi` | `1Gi` | `-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC` |
| **`guardrail-service`** | `200m` | `800m` | `512Mi` | `1Gi` | `-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC` |
| **`cache-service`** | `250m` | `1000m` | `512Mi` | `1Gi` | `-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC` |
| **`billing-service`** | `200m` | `800m` | `512Mi` | `1Gi` | `-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC` |

- **Security Hardening:** All pods enforce unprivileged user execution via `securityContext`:
  ```yaml
  securityContext:
    runAsNonRoot: true
    runAsUser: 10001
    runAsGroup: 10001
    allowPrivilegeEscalation: false
    readOnlyRootFilesystem: false
  ```

---

## 5. Horizontal Pod Autoscaling (HPA v2) & Disruption Budgets

### 5.1 Autoscaling Configuration

Autoscaling is governed by Kubernetes `autoscaling/v2` HorizontalPodAutoscalers targeting both CPU and Memory thresholds:

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: gateway-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: gateway-service
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 75
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 80
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 0
      policies:
        - type: Percent
          value: 100
          periodSeconds: 15
    scaleDown:
      stabilizationWindowSeconds: 300
      policies:
        - type: Percent
          value: 10
          periodSeconds: 60
```

- **Scale-Up Policy:** Instant scale-up (0-second stabilization window) scaling up to 100% additional capacity within 15 seconds to absorb sudden LLM traffic bursts.
- **Scale-Down Policy:** 300-second (5-minute) stabilization window to prevent flapping and retain hot semantic cache and connection pool memory.

### 5.2 Pod Disruption Budgets (PDB)

Every microservice defines a PodDisruptionBudget ensuring that during Kubernetes node maintenance, cluster upgrades, or voluntary evictions, at least one healthy pod remains operational:

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: gateway-pdb
spec:
  minAvailable: 1
  selector:
    matchLabels:
      app.kubernetes.io/name: gateway-service
```

---

## 6. Zero-Downtime Rolling Deployment Strategy

To achieve continuous deployment without dropping in-flight client requests, every Deployment specifies a strict `RollingUpdate` strategy:

```yaml
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxSurge: 25%
    maxUnavailable: 0
```

- `maxUnavailable: 0`: Guarantees that Kubernetes **never terminates an existing pod** until a new replica has passed its `startupProbe` and `readinessProbe`.
- `maxSurge: 25%`: Allocates temporary replacement capacity to spin up new pods alongside existing running pods.

---

## 7. Prometheus Metrics & Observability

Every microservice exposes OpenMetrics/Prometheus formats on `/actuator/prometheus`. Monitoring is enabled via dual integration mechanisms:

1. **Kubernetes Annotations:**
   ```yaml
   annotations:
     prometheus.io/scrape: "true"
     prometheus.io/path: "/actuator/prometheus"
     prometheus.io/port: "8080"
   ```
2. **Prometheus Operator `ServiceMonitor`:**
   ```yaml
   apiVersion: monitoring.coreos.com/v1
   kind: ServiceMonitor
   metadata:
     name: orvix-services-monitor
   spec:
     selector:
       matchLabels:
         app.kubernetes.io/part-of: orvix-ai-control-plane
     endpoints:
       - port: http
         path: /actuator/prometheus
         interval: 15s
         scrapeTimeout: 10s
   ```
3. **Dedicated Prometheus Scraper:** A self-contained Prometheus deployment and ConfigMap configured to scrape all 4 microservices across ports `8080`, `8081`, `8082`, and `8083`.

---

## 8. Empirical Verification Runbook

The deployment artifacts are verified via two distinct workflows:

### Workflow 1: Helm Chart Deployment (`helm/orvix`)
```powershell
# 1. Lint the Helm chart
docker run --rm -v "${PWD}:/workspace" -w /workspace alpine/helm:latest lint helm/orvix

# 2. Template and inspect rendered YAML
docker run --rm -v "${PWD}:/workspace" -w /workspace alpine/helm:latest template orvix helm/orvix

# 3. Deploy to Kubernetes cluster
helm upgrade --install orvix ./helm/orvix --namespace orvix-system --create-namespace
```

### Workflow 2: Standalone Raw Manifests (`k8s/`)
```powershell
# Apply all manifests in order
kubectl apply -f k8s/00-namespace.yaml
kubectl apply -f k8s/01-configmaps-secrets.yaml
kubectl apply -f k8s/02-infrastructure.yaml
kubectl apply -f k8s/03-deployments-services.yaml
kubectl apply -f k8s/04-autoscaling-hpa.yaml
kubectl apply -f k8s/05-pod-disruption-budgets.yaml
kubectl apply -f k8s/06-ingress.yaml
kubectl apply -f k8s/07-prometheus-monitoring.yaml
```

### Automated Specification Verification
```powershell
python benchmark/verify-k8s-manifests.py
```
*Expected: 30 standalone k8s resources and 29 Helm chart resources verified with 100% pass rate.*
