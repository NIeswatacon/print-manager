(ns print-manager.cost-calculator
  (:require [clojure.spec.alpha :as s]))

;; Specs para validação
(s/def ::tempo-minutos pos-int?)
(s/def ::peso-usado-g (s/and number? pos?))
(s/def ::preco-venda (s/nilable number?))

(s/def ::potencia-watts pos-int?)
(s/def ::tarifa-kwh (s/and number? pos?))
;; >>> FALTAVA ESTE SPEC <<<
(s/def ::custo-por-kg (s/and number? pos?))

(defn calcular-custo-filamento
  "Calcula o custo do filamento usado na impressão"
  [peso-usado-g custo-por-kg]
  {:pre [(s/valid? ::peso-usado-g peso-usado-g)
         (s/valid? ::custo-por-kg custo-por-kg)]}
  (* peso-usado-g (/ custo-por-kg 1000)))

(defn calcular-custo-energia
  "Calcula o custo de energia com base no tempo (min), potência (W) e tarifa (R$/kWh)."
  [tempo-minutos potencia-watts tarifa-kwh]

  ;; Validação simples, sem spec/assert
  (when-not (and (number? tempo-minutos)
                 (>= tempo-minutos 0)
                 (number? potencia-watts)
                 (pos? potencia-watts)
                 (number? tarifa-kwh)
                 (pos? tarifa-kwh))
    (throw (ex-info "Parâmetros inválidos para cálculo de energia"
                    {:tempo-minutos tempo-minutos
                     :potencia-watts potencia-watts
                     :tarifa-kwh tarifa-kwh})))

  (let [horas (/ (double tempo-minutos) 60.0)
        kwh   (/ (* (double potencia-watts) horas) 1000.0)]
    ;; tarifa-kwh provavelmente já vem como BigDecimal (por causa do config),
    ;; então converto kwh pra BigDecimal também:
    (* (bigdec kwh) (bigdec tarifa-kwh))))

(defn calcular-custo-fixo
  "Calcula o custo fixo por impressão baseado no custo mensal e impressões/mês"
  [custo-fixo-mensal impressoes-mes]
  (/ custo-fixo-mensal impressoes-mes))

(defn calcular-amortizacao
  "Calcula a amortização da impressora por impressão
   Fórmula: (valor_impressora / vida_util_horas) * (tempo_minutos / 60)"
  [tempo-minutos valor-impressora vida-util-horas]
  {:pre [(s/valid? ::tempo-minutos tempo-minutos)]}
  (let [tempo-horas (/ tempo-minutos 60.0)
        custo-por-hora (/ valor-impressora vida-util-horas)]
    (* custo-por-hora tempo-horas)))

(defn calcular-custo-total
  "Calcula o custo total da impressão incluindo margem de falhas"
  [{:keys [custo-filamento
           custo-energia
           custo-fixo
           custo-amortizacao
           custo-acessorios
           percentual-falhas]
    :or {custo-acessorios 0
         percentual-falhas 15}}]
  (let [custo-base (+ custo-filamento
                      custo-energia
                      custo-fixo
                      custo-amortizacao
                      custo-acessorios)
        margem-falhas (* custo-base (/ percentual-falhas 100.0))]
    (+ custo-base margem-falhas)))

(defn calcular-preco-consumidor
  "Calcula o preço de venda ao consumidor final
   Baseado na planilha STLFlix com markup, impostos, taxas"
  [custo-total {:keys [markup
                       imposto-percentual
                       taxa-cartao-percentual
                       custo-anuncio-percentual]
                :or {markup 5
                     imposto-percentual 8
                     taxa-cartao-percentual 5
                     custo-anuncio-percentual 20}}]
  (let [preco-base (* custo-total markup)
        total-percentuais (+ imposto-percentual
                             taxa-cartao-percentual
                             custo-anuncio-percentual)
        divisor (- 1 (/ total-percentuais 100.0))]
    (/ preco-base divisor)))

  (defn calcular-preco-lojista
    "Calcula o preço de venda para lojista (50% do preço consumidor)"
    [preco-consumidor]
    (/ preco-consumidor 2))

  (defn calcular-lucros
    "Calcula lucro bruto e líquido"
    [preco-venda custo-total {:keys [imposto-percentual
                                     taxa-cartao-percentual
                                     custo-anuncio-percentual]
                              :or {imposto-percentual 8
                                   taxa-cartao-percentual 5
                                   custo-anuncio-percentual 20}}]
    (let [lucro-bruto (- preco-venda custo-total)
          total-deducoes (* preco-venda
                            (/ (+ imposto-percentual
                                  taxa-cartao-percentual
                                  custo-anuncio-percentual)
                               100.0))
          lucro-liquido (- lucro-bruto total-deducoes)]
      {:lucro-bruto lucro-bruto
       :lucro-liquido lucro-liquido
       :margem-percentual (if (pos? preco-venda)
                            (* 100 (/ lucro-liquido preco-venda))
                            0)}))

  (defn calcular-impressao-completa
    "Calcula todos os custos e preços de uma impressão"
    [impressao config]
    (let [;; garante que vamos achar a potência, mesmo que a chave seja outra
          potencia-watts (or (:potencia-watts config)
                             (:potencia-impressora-watts config)
                             1300)          ;; fallback seguro

          tarifa-kwh     (or (:custo-kwh config)
                             (:tarifa-kwh config)
                             0.90M)

          ;; Custos individuais
          custo-filamento (calcular-custo-filamento
                            (:peso-usado-g impressao)
                            (:custo-por-kg config))

          custo-energia (calcular-custo-energia
                          (:tempo-minutos impressao)
                          potencia-watts
                          tarifa-kwh)

          custo-fixo (calcular-custo-fixo
                       (:custo-fixo-mensal config)
                       (:impressoes-mes config))

          custo-amortizacao (calcular-amortizacao
                              (:tempo-minutos impressao)
                              (:valor-impressora config)
                              (:vida-util-horas config))

          ;; Custo total
          custo-total (calcular-custo-total
                        {:custo-filamento     custo-filamento
                         :custo-energia       custo-energia
                         :custo-fixo          custo-fixo
                         :custo-amortizacao   custo-amortizacao
                         :custo-acessorios    (or (:custo-acessorios impressao) 0)
                         :percentual-falhas   (:percentual-falhas config)})

          ;; Preços sugeridos
          preco-consumidor (calcular-preco-consumidor custo-total config)
          preco-lojista    (calcular-preco-lojista preco-consumidor)

          ;; Usar preço real se fornecido, senão usar sugestão
          preco-venda (or (:preco-venda impressao) preco-consumidor)

          ;; Lucros
          lucros (calcular-lucros preco-venda custo-total config)]

      {:custo-filamento           (bigdec (format "%.2f" custo-filamento))
       :custo-energia             (bigdec (format "%.2f" custo-energia))
       :custo-fixo                (bigdec (format "%.2f" custo-fixo))
       :custo-amortizacao         (bigdec (format "%.2f" custo-amortizacao))
       :custo-total               (bigdec (format "%.2f" custo-total))
       :preco-consumidor-sugerido (bigdec (format "%.2f" preco-consumidor))
       :preco-lojista-sugerido    (bigdec (format "%.2f" preco-lojista))
       :preco-venda-real          (bigdec (format "%.2f" preco-venda))
       :lucro-bruto               (bigdec (format "%.2f" (:lucro-bruto lucros)))
       :lucro-liquido             (bigdec (format "%.2f" (:lucro-liquido lucros)))
       :margem-percentual         (bigdec (format "%.2f" (:margem-percentual lucros)))}))
