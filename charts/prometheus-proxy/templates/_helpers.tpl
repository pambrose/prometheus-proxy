{{- define "prometheus-proxy.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "prometheus-proxy.fullname" -}}
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

{{- define "prometheus-proxy.selectorLabels" -}}
app.kubernetes.io/name: {{ include "prometheus-proxy.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "prometheus-proxy.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{ include "prometheus-proxy.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "prometheus-proxy.image" -}}
{{- printf "%s:%s" .Values.image.repository (default .Chart.AppVersion .Values.image.tag) }}
{{- end }}

{{/* The ConfigMap holding proxy.conf: the user's, or the one this chart renders from config. */}}
{{- define "prometheus-proxy.configMapName" -}}
{{- default (include "prometheus-proxy.fullname" .) .Values.existingConfigMap }}
{{- end }}

{{- define "prometheus-proxy.hasConfig" -}}
{{- if or .Values.config .Values.existingConfigMap .Values.existingSecret }}true{{ end }}
{{- end }}

{{/* Whether this chart renders proxy.conf into a ConfigMap of its own: from config, when no existing one is named. */}}
{{- define "prometheus-proxy.rendersConfig" -}}
{{- if and .Values.config (not .Values.existingConfigMap) (not .Values.existingSecret) }}true{{ end }}
{{- end }}

{{/*
The dashboard values as YAML, each one the values lack at its default here, for a template to read with fromYaml.
helm upgrade --reuse-values renders this chart with the values of the release it upgrades, from that release's chart,
and 4.6.0's had no dashboard.host or dashboard.login, so reading dashboard.login failed the upgrade. The host is
127.0.0.1 when the values have no such key; given as empty, it stays empty, leaving the address to
proxy.dashboard.host in the config.
*/}}
{{- define "prometheus-proxy.dashboard" -}}
{{- $given := .Values.dashboard | default dict }}
{{- $login := $given.login | default dict }}
enabled: {{ $given.enabled | default false }}
port: {{ $given.port | default 8094 }}
host: {{ if hasKey $given "host" }}{{ $given.host | quote }}{{ else }}"127.0.0.1"{{ end }}
login:
  username: {{ $login.username | default "admin" | quote }}
  existingSecret: {{ $login.existingSecret | default "" | quote }}
  key: {{ $login.key | default "password" | quote }}
{{- end }}

{{/* The dashboard's listen address (see prometheus-proxy.dashboard). */}}
{{- define "prometheus-proxy.dashboardHost" -}}
{{- (include "prometheus-proxy.dashboard" . | fromYaml).host }}
{{- end }}

{{/* Whether the dashboard listens only on the pod's loopback, which port-forward reaches and no other pod can. */}}
{{- define "prometheus-proxy.dashboardIsLocal" -}}
{{- if has (include "prometheus-proxy.dashboardHost" .) (list "127.0.0.1" "localhost" "::1") }}true{{ end }}
{{- end }}
