{{- define "prometheus-agent.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "prometheus-agent.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "prometheus-agent.selectorLabels" -}}
app.kubernetes.io/name: {{ include "prometheus-agent.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "prometheus-agent.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{ include "prometheus-agent.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "prometheus-agent.image" -}}
{{- printf "%s:%s" .Values.image.repository (default .Chart.AppVersion .Values.image.tag) }}
{{- end }}

{{/* The ConfigMap holding agent.conf: the user's, or the one this chart renders from config. */}}
{{- define "prometheus-agent.configMapName" -}}
{{- default (include "prometheus-agent.fullname" .) .Values.existingConfigMap }}
{{- end }}

{{/* The ConfigMap holding the discovery targets.conf: the user's, or the one this chart renders from targets. */}}
{{- define "prometheus-agent.discoveryConfigMapName" -}}
{{- default (printf "%s-discovery" (include "prometheus-agent.fullname" .)) .Values.discovery.existingConfigMap }}
{{- end }}

{{/*
The dashboard values as YAML, each one the values lack at its default here, for a template to read with fromYaml.
helm upgrade --reuse-values renders this chart with the values of the release it upgrades, from that release's chart,
and 4.6.0's had no dashboard at all, so reading dashboard.enabled failed the upgrade. The host is 127.0.0.1 when the
values have no such key; given as empty, it stays empty, leaving the address to agent.dashboard.host in the config.
*/}}
{{- define "prometheus-agent.dashboard" -}}
{{- $given := .Values.dashboard | default dict }}
{{- $login := $given.login | default dict }}
enabled: {{ $given.enabled | default false }}
port: {{ $given.port | default 8095 }}
host: {{ if hasKey $given "host" }}{{ $given.host | quote }}{{ else }}"127.0.0.1"{{ end }}
login:
  username: {{ $login.username | default "admin" | quote }}
  existingSecret: {{ $login.existingSecret | default "" | quote }}
  key: {{ $login.key | default "password" | quote }}
{{- end }}

{{/* The dashboard's listen address (see prometheus-agent.dashboard). */}}
{{- define "prometheus-agent.dashboardHost" -}}
{{- (include "prometheus-agent.dashboard" . | fromYaml).host }}
{{- end }}

{{/* Whether the dashboard listens only on the pod's loopback, which port-forward reaches and no other pod can. */}}
{{- define "prometheus-agent.dashboardIsLocal" -}}
{{- if has (include "prometheus-agent.dashboardHost" .) (list "127.0.0.1" "localhost" "::1") }}true{{ end }}
{{- end }}
