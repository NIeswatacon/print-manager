(ns print-manager.cost-calculator
  (:require [clojure.spec.alpha :as s]))

;; Specs para validação
(s/def ::tempo-minutos pos-int?)
(s/def ::peso-usado-g pos?)
(s/def ::custo-por-kg pos?)
(s/def ::potencia-watts pos-int?)
(s/def ::custo-kwh pos?)

(defn calcular-custo-filamento
  "Calcula o custo do filamento usado na impressão"
  [peso-usado-g custo-por-kg]
  {:pre [(s/valid? ::peso-usado-g peso-usado-g)
         (s/valid? ::custo-por-kg custo-por-kg)]}
  (* peso-usado-g (/ custo-por-kg 1000)))

(defn calcular-custo-energia
  "Calcula o custo de energia da impressão
   Fórmula: (potencia_watts / 1000) * (tempo_minutos / 60) * custo_kwh"
  [tempo-minutos potencia-watts custo-kwh]
  {:pre [(s/valid? ::tempo-minutos tempo-minutos)
         (s/valid? ::potencia-watts potencia-watts)
         (s/valid? ::custo-kwh custo-kwh)]}
  (let [tempo-horas (/ tempo-minutos 60.0)
        kwh-consumido (* (/ potencia-watts 1000.0) tempo-horas)]
    (* kwh-consumido custo-kwh)))

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
  (let [;; Custos individuais
        custo-filamento (calcular-custo-filamento
                          (:peso-usado-g impressao)
                          (:custo-por-kg config))

        custo-energia (calcular-custo-energia
                        (:tempo-minutos impressao)
                        (:potencia-watts config)
                        (:custo-kwh config))

        custo-fixo (calcular-custo-fixo
                     (:custo-fixo-mensal config)
                     (:impressoes-mes config))

        custo-amortizacao (calcular-amortizacao
                            (:tempo-minutos impressao)
                            (:valor-impressora config)
                            (:vida-util-horas config))

        ;; Custo total
        custo-total (calcular-custo-total
                      {:custo-filamento custo-filamento
                       :custo-energia custo-energia
                       :custo-fixo custo-fixo
                       :custo-amortizacao custo-amortizacao
                       :custo-acessorios (or (:custo-acessorios impressao) 0)
                       :percentual-falhas (:percentual-falhas config)})

        ;; Preços sugeridos
        preco-consumidor (calcular-preco-consumidor custo-total config)
        preco-lojista (calcular-preco-lojista preco-consumidor)

        ;; Usar preço real se fornecido, senão usar sugestão
        preco-venda (or (:preco-venda impressao) preco-consumidor)

        ;; Lucros
        lucros (calcular-lucros preco-venda custo-total config)]

    {:custo-filamento (bigdec (format "%.2f" custo-filamento))
     :custo-energia (bigdec (format "%.2f" custo-energia))
     :custo-fixo (bigdec (format "%.2f" custo-fixo))
     :custo-amortizacao (bigdec (format "%.2f" custo-amortizacao))
     :custo-total (bigdec (format "%.2f" custo-total))
     :preco-consumidor-sugerido (bigdec (format "%.2f" preco-consumidor))
     :preco-lojista-sugerido (bigdec (format "%.2f" preco-lojista))
     :preco-venda-real (bigdec (format "%.2f" preco-venda))
     :lucro-bruto (bigdec (format "%.2f" (:lucro-bruto lucros)))
     :lucro-liquido (bigdec (format "%.2f" (:lucro-liquido lucros)))
     :margem-percentual (bigdec (format "%.2f" (:margem-percentual lucros)))}))

(comment
  ;; Exemplo de uso:

  (def config
    {:custo-por-kg 100.0
     :potencia-watts 1300
     :custo-kwh 0.90
     :custo-fixo-mensal 300.0
     :impressoes-mes 40
     :valor-impressora 4500.0
     :vida-util-horas 20000
     :percentual-falhas 15
     :markup 5
     :imposto-percentual 8
     :taxa-cartao-percentual 5
     :custo-anuncio-percentual 20})

  (def impressao
    {:tempo-minutos 480
     :peso-usado-g 100
     :custo-acessorios 0
     :preco-venda nil}) ; nil = usar preço sugerido

  (calcular-impressao-completa impressao config)
  ;; => {:custo-filamento 10.00M
  ;;     :custo-energia 9.36M
  ;;     :custo-fixo 7.50M
  ;;     :custo-amortizacao 1.80M
  ;;     :custo-total 33.06M
  ;;     :preco-consumidor-sugerido 245.97M
  ;;     ...}
  )
