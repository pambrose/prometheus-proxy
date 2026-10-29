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
