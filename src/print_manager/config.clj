{;; Configurações do Banco de Dados
 :database {:host "localhost"
            :port 5432
            :dbname "print_manager"
            :user "postgres"
            :password "postgres"}

 ;; Configurações do Servidor
 :server {:port 3000
          :host "0.0.0.0"}

 ;; Configurações de Custos (valores iniciais)
 ;; Estes valores podem ser alterados via API depois
 :custos {:potencia-impressora-watts 1300
          :custo-kwh 0.90M
          :custo-fixo-mensal 300.00M
          :impressoes-mes 40
          :valor-impressora 4500.00M
          :vida-util-horas 20000
          :percentual-falhas 15
          :markup-padrao 5M
          :imposto-percentual 8M
          :taxa-cartao-percentual 5M
          :custo-anuncio-percentual 20M}

 ;; Credenciais Bambu Lab (opcional - pode autenticar via API)
 ;; IMPORTANTE: Não commite este arquivo com credenciais reais!
 :bambu {:email nil
         :password nil}

 ;; Configurações de Log
 :logging {:level :info  ; :debug, :info, :warn, :error
           :console true
           :file "logs/print-manager.log"}

 ;; Agendamento de sincronização automática
 :sync {:auto-sync true
        :interval-minutes 60  ; Sincronizar a cada 60 minutos
        :on-startup true}     ; Sincronizar ao iniciar

 ;; Notificações (futuro)
 :notifications {:email {:enabled false
                         :smtp-host nil
                         :smtp-port 587
                         :from nil}
                 :webhook {:enabled false
                           :url nil}}}