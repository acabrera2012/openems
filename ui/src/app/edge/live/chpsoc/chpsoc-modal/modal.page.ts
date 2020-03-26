import { Component, OnInit, Input } from '@angular/core';
import { ModalController } from '@ionic/angular';
import { RangeValue } from '@ionic/core';
import { Router } from '@angular/router';
import { TranslateService } from '@ngx-translate/core';
import { Websocket, Service, EdgeConfig, Edge, ChannelAddress } from 'src/app/shared/shared';

type mode = 'MANUAL_ON' | 'MANUAL_OFF' | 'AUTOMATIC';


@Component({
    selector: ChpsocModalComponent.SELECTOR,
    templateUrl: './modal.page.html'
})
export class ChpsocModalComponent implements OnInit {

    private static readonly SELECTOR = "chpsoc-modal";

    @Input() public edge: Edge;
    @Input() public component: EdgeConfig.Component;
    @Input() public outputChannel: ChannelAddress;
    @Input() public inputChannel: ChannelAddress;

    public thresholds: RangeValue = {
        lower: null,
        upper: null
    };

    constructor(
        protected service: Service,
        public websocket: Websocket,
        public router: Router,
        protected translate: TranslateService,
        public modalCtrl: ModalController,
    ) { }

    ngOnInit() {
        this.thresholds['lower'] = this.component.properties['lowThreshold'];
        this.thresholds['upper'] = this.component.properties['highThreshold'];
    };

    /**  
    * Updates the Charge-Mode of the EVCS-Controller.
    * 
    * @param event 
    */
    updateMode(event: CustomEvent) {
        let oldMode = this.component.properties.mode;
        let newMode: mode;

        switch (event.detail.value) {
            case 'MANUAL_ON':
                newMode = 'MANUAL_ON';
                break;
            case 'MANUAL_OFF':
                newMode = 'MANUAL_OFF';
                break;
            case 'AUTOMATIC':
                newMode = 'AUTOMATIC';
                break;
        }

        if (this.edge != null) {
            this.edge.updateComponentConfig(this.websocket, this.component.id, [
                { name: 'mode', value: newMode }
            ]).then(() => {
                this.component.properties.mode = newMode;
                this.service.toast(this.translate.instant('General.changeAccepted'), 'success');
            }).catch(reason => {
                this.component.properties.mode = oldMode;
                this.service.toast(this.translate.instant('General.changeFailed') + '\n' + reason.error.message, 'danger');
                console.warn(reason);
            });
        }
    }

    /**
    * Updates the Min-Power of force charging
    *
    * @param event
    */
    updateThresholds() {
        let oldLowerThreshold = this.component.properties['lowThreshold'];
        let oldUpperThreshold = this.component.properties['highThreshold'];

        let newLowerThreshold = this.thresholds['lower'];
        let newUpperThreshold = this.thresholds['upper'];

        if (this.edge != null) {
            this.edge.updateComponentConfig(this.websocket, this.component.id, [
                { name: 'lowThreshold', value: newLowerThreshold },
                { name: 'highThreshold', value: newUpperThreshold }
            ]).then(() => {
                this.component.properties['lowThreshold'] = newLowerThreshold;
                this.component.properties['highThreshold'] = newUpperThreshold;
                this.service.toast(this.translate.instant('General.changeAccepted'), 'success');
            }).catch(reason => {
                this.component.properties['lowThreshold'] = oldLowerThreshold;
                this.component.properties['highThreshold'] = oldUpperThreshold;
                this.service.toast(this.translate.instant('General.changeFailed') + '\n' + reason.error.message, 'danger');
                console.warn(reason);
            })
        }
    }
}


