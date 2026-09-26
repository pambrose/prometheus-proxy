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
