# Docker 

```bash
$ docker build -f proxy.df -t pambrose/prometheus-proxy .
$ docker build -f agent.df -t pambrose/prometheus-agent .

$ docker push haptava/builder

$ docker run -it --rm haptava/builder
```
